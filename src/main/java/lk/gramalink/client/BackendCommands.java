package lk.gramalink.client;

import lk.gramalink.shared.*;

/** Exact STATUS_UPDATE/CHAT_MESSAGE fields from backend-contract.md. No local state transitions. */
final class BackendCommands {
    private BackendCommands() { }
    static Message chat(String id,String note) {
        if(note==null || note.isBlank() || note.length()>4000) throw new IllegalArgumentException("Enter a message of 1–4000 characters.");
        return BackendQueries.command(MessageType.CHAT_MESSAGE,BackendQueries.object().put("requestId",id).put("body",note));
    }
    static Message change(String id,Long version,String action,String reason) {
        if(version==null || version<0) throw new IllegalArgumentException("Reload the authoritative request version before deciding.");
        var p=BackendQueries.object().put("requestId",id).put("expectedVersion",version.longValue()).put("action",action);
        if(reason!=null && !reason.isBlank()) {
            if(reason.length()>1000) throw new IllegalArgumentException("Reasons must contain at most 1000 characters.");
            p.put("reason",reason);
        }
        return BackendQueries.command(MessageType.STATUS_UPDATE,p);
    }
    static long version(DsData.Version version) {
        if(version==null || !version.value().matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Reload the numeric server version.");
        try { return Long.parseLong(version.value()); }
        catch(NumberFormatException e){ throw new IllegalArgumentException("Invalid server version."); }
    }
}
