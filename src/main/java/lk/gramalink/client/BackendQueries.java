package lk.gramalink.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import lk.gramalink.shared.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** The existing backend contract, consumed on a gateway worker, never on the FX or receiver thread. */
final class BackendQueries {
    private final NetworkClient client;
    private final Runnable requireCurrent;
    BackendQueries(NetworkClient client, Runnable requireCurrent) {
        this.client = client; this.requireCurrent = requireCurrent;
    }
    static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    static Message command(MessageType type, ObjectNode payload) {
        return new Message(type, UUID.randomUUID().toString(), null, payload);
    }
    JsonNode call(MessageType type, ObjectNode payload) throws IOException {
        requireCurrent.run();
        Message sent = command(type, payload);
        try {
            Message reply = client.request(sent, Duration.ofSeconds(10)).get(12, TimeUnit.SECONDS);
            requireCurrent.run();
            return result(sent, reply);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Query interrupted.", e); }
        catch (ExecutionException | TimeoutException e) { throw new IOException("Server query did not complete. Reload authoritative data before retrying.", e); }
    }
    static JsonNode result(Message sent, Message reply) throws IOException {
        if (!Objects.equals(sent.correlationId(), reply.correlationId()) || reply.token() != null)
            throw new IOException("Invalid server response correlation.");
        if (reply.type() == MessageType.ERROR) throw new IOException(LoginService.describe(reply));
        if (reply.type() != MessageType.ACK || !sent.type().name().equals(reply.payload().path("operation").asText())
                || !reply.payload().has("data")) throw new IOException("The server response does not match the backend contract.");
        return reply.payload().get("data");
    }
    List<JsonNode> pages(MessageType type, ObjectNode payload, int size) throws IOException {
        LinkedHashMap<String, JsonNode> records = new LinkedHashMap<>();
        for (int offset = 0; ; offset = Math.addExact(offset, size)) {
            JsonNode page = call(type, payload.deepCopy().put("offset", offset));
            if (!page.isArray() || page.size() > size) throw new IOException("Invalid paged response.");
            for (JsonNode row : page) {
                if (!row.hasNonNull("id") || !row.get("id").isValueNode()) throw new IOException("Missing record ID.");
                records.put(row.get("id").asText(), row);
            }
            if (page.size() < size) return List.copyOf(records.values());
        }
    }
    List<JsonNode> notifications(String userId) throws IOException {
        List<JsonNode> result = new ArrayList<>();
        long cursor = 0;
        while (true) {
            JsonNode page = call(MessageType.GET_NOTIFICATIONS, object().put("afterId", cursor));
            if (!page.isArray() || page.size() > 100) throw new IOException("Invalid notifications response.");
            for (JsonNode row : page) {
                if (!userId.equals(text(row, "user_id")) || !row.path("id").isIntegralNumber() || row.path("id").longValue() <= cursor)
                    throw new IOException("Invalid notification ownership or cursor.");
                cursor = row.path("id").longValue(); result.add(row);
            }
            if (page.size() < 100) return result;
        }
    }
    List<CitizenData.Update> citizen(String userId) throws IOException {
        JsonNode me = call(MessageType.ME, object());
        if (!userId.equals(text(me, "id")) || !"CITIZEN".equals(text(me, "role")))
            throw new IOException("The current server identity changed. Sign in again.");
        List<JsonNode> rows = pages(MessageType.LIST_REQUESTS, object(), 50);
        List<CitizenData.RequestRow> requests = new ArrayList<>();
        List<CitizenData.Update> details = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!userId.equals(text(row, "citizen_id")) || !text(me, "division").equals(text(row, "division")))
                throw new IOException("Out-of-scope request response rejected.");
            String id = text(row, "id");
            JsonNode request = call(MessageType.GET_REQUEST, object().put("requestId", id));
            if (!id.equals(text(request, "id")) || !userId.equals(text(request, "citizenId")))
                throw new IOException("Out-of-scope request details rejected.");
            List<CitizenData.StatusEntry> history = new ArrayList<>();
            for (JsonNode h : pages(MessageType.GET_HISTORY, object().put("requestId", id), 100)) {
                sameRequest(h, id);
                history.add(new CitizenData.StatusEntry(at(h), text(h, "to_status"), text(h, "action") + reason(h)));
            }
            List<CitizenData.ChatEntry> messages = new ArrayList<>();
            for (JsonNode m : pages(MessageType.GET_CHAT, object().put("requestId", id), 50)) {
                sameRequest(m, id);
                messages.add(new CitizenData.ChatEntry(at(m), text(m, "sender_id"), text(m, "body")));
            }
            if(!request.path("version").isIntegralNumber() || !request.path("version").canConvertToLong() || request.path("version").longValue()<0)
                throw new IOException("Missing authoritative request version.");
            requests.add(new CitizenData.RequestRow(id, text(request, "type"), at(row), text(request, "status"),request.path("version").longValue()));
            List<CitizenData.Document> documents=new ArrayList<>();
            for(JsonNode d:pages(MessageType.LIST_DOCUMENTS,object().put("requestId",id),100)) {
                sameRequest(d,id); documents.add(new CitizenData.Document(text(d,"id"),text(d,"original_name")));
            }
            details.add(new CitizenData.RequestDetails(new CitizenData.Details(id,
                    text(request, "title") + "\n" + text(request, "description"), history, messages,documents)));
        }
        List<CitizenData.Notification> notices = new ArrayList<>();
        for (JsonNode n : notifications(userId)) notices.add(new CitizenData.Notification(at(n), text(n, "body"), nullable(n, "request_id")));
        List<CitizenData.Update> updates = new ArrayList<>();
        updates.add(new CitizenData.Profile(text(me, "division")));
        updates.add(new CitizenData.Requests(requests)); updates.addAll(details); updates.add(new CitizenData.Notifications(notices));
        requireCurrent.run(); return updates;
    }
    static void sameRequest(JsonNode row, String id) throws IOException {
        if (!id.equals(text(row, "request_id"))) throw new IOException("Out-of-scope request data rejected.");
    }
    List<Object> officer(String role, String userId) throws IOException {
        JsonNode me=call(MessageType.ME, object());
        if (!userId.equals(text(me,"id")) || !role.equals(text(me,"role"))) throw new IOException("Server identity changed. Sign in again.");
        boolean gn=role.equals("GN");
        List<GnData.RequestRow> gnRows=new ArrayList<>(); List<DsData.RequestRow> dsRows=new ArrayList<>();
        List<Object> details=new ArrayList<>();
        for(JsonNode row:pages(MessageType.LIST_REQUESTS,object(),50)) {
            String id=text(row,"id");
            String prefix=gn ? "gn" : "ds";
            if (!userId.equals(text(row,prefix+"_id")) || !text(me,"officeId").equals(text(row,prefix+"_office_id"))
                    || (gn && !text(me,"division").equals(text(row,"division")))) throw new IOException("Out-of-scope officer inbox rejected.");
            JsonNode r=call(MessageType.GET_REQUEST,object().put("requestId",id));
            if (!id.equals(text(r,"id")) || !userId.equals(text(r,prefix+"Id"))
                    || !text(me,"officeId").equals(text(r,prefix+"OfficeId"))) throw new IOException("Out-of-scope officer details rejected.");
            if (!r.path("version").isIntegralNumber() || !r.path("version").canConvertToLong() || r.path("version").longValue()<0)
                throw new IOException("Missing authoritative numeric request version.");
            long version=r.path("version").longValue();
            var history=pages(MessageType.GET_HISTORY,object().put("requestId",id),100);
            var chat=pages(MessageType.GET_CHAT,object().put("requestId",id),50);
            var documents=pages(MessageType.LIST_DOCUMENTS,object().put("requestId",id),100);
            List<GnData.Document> gd=new ArrayList<>(); List<DsData.Document> dd=new ArrayList<>();
            for(JsonNode d:documents){ sameRequest(d,id); gd.add(new GnData.Document(text(d,"id"),text(d,"original_name"),"Stored; no document verification contract")); dd.add(new DsData.Document(text(d,"id"),text(d,"original_name"),"Stored")); }
            List<GnData.History> gh=new ArrayList<>(); List<DsData.History> dh=new ArrayList<>();
            for(JsonNode h:history){ sameRequest(h,id); gh.add(new GnData.History(at(h),text(h,"to_status"),text(h,"action")+reason(h))); dh.add(new DsData.History(at(h),text(h,"to_status"),text(h,"action")+reason(h))); }
            List<GnData.Chat> gc=new ArrayList<>(); List<DsData.Chat> dc=new ArrayList<>();
            for(JsonNode m:chat){ sameRequest(m,id); gc.add(new GnData.Chat(at(m),text(m,"sender_id"),text(m,"body"))); dc.add(new DsData.Chat(at(m),text(m,"sender_id"),text(m,"body"))); }
            String description=text(r,"title")+"\n"+text(r,"description");
            if(gn){
                gnRows.add(new GnData.RequestRow(id,text(r,"citizenId"),text(r,"type"),at(row),text(r,"status"),null,version));
                details.add(new GnData.RequestDetails(new GnData.Details(id,description,gd,gh,gc)));
            } else {
                var revision=new DsData.Version(Long.toString(version));
                dsRows.add(new DsData.RequestRow(id,text(r,"citizenId"),text(r,"gnOfficeId"),text(r,"type"),text(r,"status"),at(row),revision));
                details.add(new DsData.RequestDetails(new DsData.Details(id,revision,description,text(r,"gnId"),dd,dh,dc)));
            }
        }
        List<Object> updates=new ArrayList<>();
        updates.add(gn ? new GnData.Requests(gnRows) : new DsData.Requests(dsRows)); updates.addAll(details);
        List<GnData.Notification> gns=new ArrayList<>(); List<DsData.Notification> dns=new ArrayList<>();
        for(JsonNode n:notifications(userId)){ gns.add(new GnData.Notification(at(n),text(n,"body"),nullable(n,"request_id"))); dns.add(new DsData.Notification(at(n),text(n,"body"),nullable(n,"request_id"))); }
        updates.add(gn ? new GnData.Notifications(gns) : new DsData.Notifications(dns));
        requireCurrent.run(); return updates;
    }
    List<AdminData.Update> administrator(String userId) throws IOException {
        JsonNode me=call(MessageType.ME,object());
        if(!userId.equals(text(me,"id")) || !"ADMIN".equals(text(me,"role"))) throw new IOException("Administrator identity changed. Sign in again.");
        List<AdminData.UserRow> users=new ArrayList<>();
        for(JsonNode row:pages(MessageType.LIST_USERS,object(),100)) {
            String role=text(row,"role");
            if(!Set.of("CITIZEN","GN","DS","ADMIN").contains(role) || !row.path("active").isBoolean()) throw new IOException("Invalid account response.");
            users.add(new AdminData.UserRow(text(row,"id"),text(row,"username"),null,new AdminData.Choice(role,role),nullable(row,"office_id"),row.path("active").booleanValue()?"Active":"Inactive",false));
        }
        List<AdminData.OfficeRow> offices=new ArrayList<>();
        for(JsonNode row:pages(MessageType.LIST_OFFICES,object(),100)) offices.add(new AdminData.OfficeRow(text(row,"id"),text(row,"name"),new AdminData.Choice(text(row,"kind"),text(row,"kind")),text(row,"division"),null,false));
        JsonNode stats=call(MessageType.GET_STATISTICS,object());
        if(!stats.path("requestsByStatus").isArray() || !stats.path("usersByRole").isArray()
                || !stats.path("onlineConnections").isIntegralNumber() || stats.path("onlineConnections").longValue()<0) throw new IOException("Invalid backend statistics.");
        List<AdminData.StatusCount> counts=new ArrayList<>(); long total=0,userTotal=0;
        for(JsonNode count:stats.path("requestsByStatus")){ long n=count(count); total=Math.addExact(total,n); counts.add(new AdminData.StatusCount(text(count,"status"),n)); }
        for(JsonNode count:stats.path("usersByRole")) userTotal=Math.addExact(userTotal,count(count));
        List<AdminData.AuditEntry> audit=new ArrayList<>(); long cursor=0;
        while(true){
            JsonNode page=call(MessageType.GET_AUDIT,object().put("afterId",cursor));
            if(!page.isArray() || page.size()>100) throw new IOException("Invalid audit response.");
            for(JsonNode row:page){
                if(!row.path("id").isIntegralNumber() || row.path("id").longValue()<=cursor) throw new IOException("Invalid audit cursor.");
                cursor=row.path("id").longValue(); audit.add(new AdminData.AuditEntry(at(row),nullable(row,"actor_id"),text(row,"action"),nullable(row,"request_id"),text(row,"outcome")));
            }
            if(page.size()<100) break;
        }
        var roles=List.of("CITIZEN","GN","DS","ADMIN").stream().map(x->new AdminData.Choice(x,x)).toList();
        var kinds=List.of("GN","DS").stream().map(x->new AdminData.Choice(x,x)).toList();
        requireCurrent.run();
        return List.of(new AdminData.Catalogues(roles,kinds),new AdminData.Users(users),new AdminData.Offices(offices),
                new AdminData.Overview(stats.path("onlineConnections").longValue(),userTotal,null,total,null),
                new AdminData.Statistics(counts,total,"All requests (backend GET_STATISTICS)",null),new AdminData.Audit(audit));
    }
    private static long count(JsonNode row) throws IOException {
        if(!row.path("count").isIntegralNumber() || !row.path("count").canConvertToLong() || row.path("count").longValue()<0) throw new IOException("Invalid backend count.");
        return row.path("count").longValue();
    }
    static String text(JsonNode node, String key) throws IOException {
        if (!node.path(key).isTextual() || node.path(key).textValue().isBlank()) throw new IOException("Missing server field: " + key);
        return node.path(key).textValue();
    }
    static String nullable(JsonNode node, String key) { return node.hasNonNull(key) ? node.path(key).asText() : null; }
    static String reason(JsonNode row) { return row.hasNonNull("reason") ? ": " + row.path("reason").asText() : ""; }
    static Instant at(JsonNode row) throws IOException {
        String value = text(row, "created_at");
        try { return Instant.parse(value); }
        catch (java.time.format.DateTimeParseException ignored) {
            // Sql.rows currently serializes java.sql.Timestamp without an offset. This local demo
            // assumes client/server share a timezone; an explicit offset requires an owner contract change.
            try { return java.sql.Timestamp.valueOf(value).toInstant(); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid server timestamp.", e); }
        }
    }
}
