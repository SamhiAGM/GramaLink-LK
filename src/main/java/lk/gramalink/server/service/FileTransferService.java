package lk.gramalink.server.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.server.SessionManager;
import lk.gramalink.server.backend.Models.*;
import lk.gramalink.server.backend.ServiceException;
import lk.gramalink.server.backend.Validation;
import lk.gramalink.server.repository.*;
import lk.gramalink.shared.*;
import org.apache.pdfbox.Loader;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** PDF-only demo storage. Transport owns sequencing; backend independently enforces byte/hash limits. */
public final class FileTransferService extends ServiceSupport implements lk.gramalink.server.FileTransferService {
    private final SessionManager sessions;
    private final Path root;
    private final long maxBytes;
    private final Set<String> allowedTypes;
    private final ConcurrentHashMap<UUID, Upload> uploads = new ConcurrentHashMap<>();
    private record Download(SessionManager.Session session, Grant grant, UUID documentId) { }
    private final ConcurrentHashMap<UUID, Download> downloads = new ConcurrentHashMap<>();
    private static final class Upload {
        final SessionManager.Session session; final Grant grant; final UUID request; final String name, expectedHash;
        final Path temporary; final MessageDigest digest; final OutputStream output;
        long bytes, sequence;
        Upload(SessionManager.Session session, Grant grant, UUID request, String name, String hash, Path temporary) throws IOException {
            this.session=session; this.grant=grant; this.request=request; this.name=name; this.expectedHash=hash; this.temporary=temporary;
            try { digest=MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            output=Files.newOutputStream(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
    }
    public FileTransferService(Database db, SessionManager sessions, Path storage, long maxBytes, Set<String> allowedTypes) throws IOException {
        super(db); this.sessions=sessions;
        if (maxBytes < 1 || maxBytes > 100L*1024*1024) throw new IllegalArgumentException("File limit must be 1..100 MiB.");
        if (allowedTypes.isEmpty() || !Set.of("application/pdf").containsAll(allowedTypes))
            throw new IllegalArgumentException("This prototype supports application/pdf only.");
        this.maxBytes=maxBytes; this.allowedTypes=Set.copyOf(allowedTypes);
        Path path=storage.toAbsolutePath().normalize(); Files.createDirectories(path);
        if (Files.isSymbolicLink(path)) throw new IOException("Storage cannot be a symbolic link.");
        root=path.toRealPath(); privatePermissions(root, true);
    }
    private static void privatePermissions(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        else {
            var view=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if (view==null) throw new IOException("Storage requires POSIX permissions or Windows ACL support.");
            var owner=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT);
            view.setAcl(List.of(entry.build()));
        }
    }
    private Actor verified(SessionManager.Session session) {
        if (session == null || sessions.findSession(session.connection()).orElse(null) != session)
            throw new ServiceException(ServiceException.Code.UNAUTHENTICATED,"An active session is required.");
        return new Actor(Validation.id(session.identity().userId()));
    }
    @Override public Grant authorize(SessionManager.Session session, Message metadata) throws IOException {
        try { return authorizeInternal(session,metadata); }
        catch (ServiceException e) {
            Actor actor=session==null ? null : new Actor(Validation.id(session.identity().userId()));
            rejected(actor,"FILE_AUTHORIZE",e); throw e;
        }
    }
    private Grant authorizeInternal(SessionManager.Session session, Message metadata) throws IOException {
        Actor actor=verified(session); var p=metadata.payload();
        String direction=p.path("direction").asText();
        if (direction.equals("DOWNLOAD")) {
            UUID id=Validation.id(p.path("documentId").asText());
            var doc=document(actor,id);
            Grant grant=new Grant(UUID.randomUUID(),Direction.DOWNLOAD,((Number)doc.get("size_bytes")).longValue());
            downloads.put(grant.transferId(),new Download(session,grant,id)); return grant;
        }
        if (!direction.equals("UPLOAD")) throw ServiceException.invalid("direction must be UPLOAD or DOWNLOAD.");
        UUID request=Validation.id(p.path("requestId").asText());
        String name=Validation.text(p.path("fileName").asText(),180,"fileName");
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9 _().-]*\\.[pP][dD][fF]") || name.contains("..") || name.endsWith(" "))
            throw ServiceException.invalid("Use a simple PDF filename without path components.");
        if (!allowedTypes.contains(p.path("mediaType").asText())) throw ServiceException.invalid("Only PDF documents are enabled.");
        if (!p.path("totalBytes").isIntegralNumber() || !p.path("totalBytes").canConvertToLong()) throw ServiceException.invalid("totalBytes must be an integer.");
        long total=p.path("totalBytes").longValue();
        if (total<1 || total>maxBytes) throw ServiceException.invalid("File exceeds the configured size limit.");
        String hash=p.path("sha256").asText();
        if (!hash.matches("[a-f0-9]{64}")) throw ServiceException.invalid("A lowercase SHA-256 checksum is required.");
        guarded(actor,"FILE_AUTHORIZE",c -> { User u=actor(c,actor); Request r=requests.require(c,request); access(u,r);
            if (u.role()==Role.ADMIN || r.status()==Status.APPROVED || r.status()==Status.REJECTED) throw ServiceException.forbidden(); return null; });
        UUID id=UUID.randomUUID(); Grant grant=new Grant(id,Direction.UPLOAD,total); Path temp=root.resolve(id+".part");
        Files.createFile(temp); privatePermissions(temp,false);
        try { uploads.put(id,new Upload(session,grant,request,name,hash,temp)); }
        catch (IOException | RuntimeException e) { Files.deleteIfExists(temp); throw e; }
        return grant;
    }
    @Override public void receiveChunk(SessionManager.Session session, Grant grant, FileChunk chunk) throws IOException {
        Actor actor=verified(session); Upload upload=uploads.get(grant.transferId());
        if (upload==null || upload.session!=session || !upload.grant.equals(grant)) throw ServiceException.forbidden();
        synchronized(upload) {
            try {
                byte[] bytes=chunk.data();
                if (!chunk.transferId().equals(grant.transferId()) || chunk.sequence()!=upload.sequence || upload.bytes+bytes.length>grant.totalBytes())
                    throw ServiceException.invalid("Invalid upload chunk.");
                upload.output.write(bytes); upload.digest.update(bytes); upload.bytes+=bytes.length; upload.sequence++;
                if (chunk.last()) {
                    if (upload.bytes!=grant.totalBytes()) throw ServiceException.invalid("Incomplete file.");
                    upload.output.close();
                    String hash=HexFormat.of().formatHex(upload.digest.digest());
                    if (!MessageDigest.isEqual(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII), upload.expectedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                        throw ServiceException.invalid("File checksum mismatch.");
                    validatePdf(upload.temporary);
                    Path destination=root.resolve(grant.transferId()+".pdf");
                    try {
                        guarded(actor,"FILE_COMPLETE",c -> {
                            User u=actor(c,actor); Request r=requests.require(c,upload.request); access(u,r);
                            if (u.role()==Role.ADMIN || r.status()==Status.APPROVED || r.status()==Status.REJECTED) throw ServiceException.forbidden();
                            try { Files.move(upload.temporary,destination,StandardCopyOption.ATOMIC_MOVE); }
                            catch (IOException e) { throw new ServiceException(ServiceException.Code.INTERNAL_ERROR,"Unable to store document."); }
                            Sql.update(c,"INSERT INTO documents(id,request_id,uploaded_by,original_name,storage_name,media_type,size_bytes,sha256) VALUES (?,?,?,?,?,'application/pdf',?,?)",
                                    grant.transferId(),r.id(),u.id(),upload.name,destination.getFileName().toString(),upload.bytes,hash);
                            audit(c,u.id(),r.id(),"FILE_COMPLETE","SUCCESS"); return null;
                        });
                    } catch (RuntimeException e) { Files.deleteIfExists(destination); throw e; }
                    uploads.remove(grant.transferId(),upload);
                } else if (upload.bytes==grant.totalBytes()) throw ServiceException.invalid("Missing final flag.");
            } catch (IOException | RuntimeException e) {
                abort(session,grant);
                if (e instanceof ServiceException service) rejected(actor,"FILE_UPLOAD",service);
                throw e;
            }
        }
    }
    private void validatePdf(Path path) throws IOException {
        byte[] header=new byte[5];
        try (InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            if (in.readNBytes(header,0,5)!=5 || !Arrays.equals(header,new byte[]{'%','P','D','F','-'})) throw ServiceException.invalid("Content is not a PDF.");
        }
        try (var pdf=Loader.loadPDF(path.toFile())) {
            if (pdf.isEncrypted() || pdf.getNumberOfPages()<1 || pdf.getNumberOfPages()>100)
                throw ServiceException.invalid("Use an unencrypted PDF containing 1..100 pages.");
            if (pdf.getDocumentCatalog().getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.OPEN_ACTION)
                    || pdf.getDocumentCatalog().getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.AA)
                    || pdf.getDocumentCatalog().getNames()!=null)
                throw ServiceException.invalid("Active content and embedded files are not accepted in demo PDFs.");
            for (var page:pdf.getPages()) if (!page.getAnnotations().isEmpty())
                throw ServiceException.invalid("Use a plain demo PDF without annotations or actions.");
        } catch (ServiceException e) { throw e; }
        catch (IOException | RuntimeException e) { throw ServiceException.invalid("PDF content could not be validated."); }
    }
    @Override public void abort(SessionManager.Session session, Grant grant) {
        downloads.computeIfPresent(grant.transferId(),(id,value) -> value.session()==session ? null : value);
        Upload upload=uploads.get(grant.transferId());
        if (upload==null || upload.session!=session) return;
        synchronized(upload) {
            if (!uploads.remove(grant.transferId(),upload)) return;
            try { upload.output.close(); } catch (IOException ignored) { }
            try { Files.deleteIfExists(upload.temporary); } catch (IOException e) { System.err.println("Unable to remove incomplete upload."); }
        }
    }
    public List<Map<String,Object>> list(Actor actor,UUID request) {
        return list(actor,request,0);
    }
    public List<Map<String,Object>> list(Actor actor,UUID request,int offset) {
        if (offset < 0) throw ServiceException.invalid("offset must be nonnegative.");
        return guarded(actor,"LIST_DOCUMENTS",c -> { User u=actor(c,actor); access(u,requests.require(c,request));
            return Sql.rows(c,"SELECT id,request_id,uploaded_by,original_name,media_type,size_bytes,sha256,created_at FROM documents WHERE request_id=? ORDER BY created_at,id LIMIT 100 OFFSET ?",request,offset); });
    }
    private Map<String,Object> document(Actor actor,UUID id) {
        return guarded(actor,"GET_DOCUMENT",c -> {
            User u=actor(c,actor); var docs=Sql.rows(c,"SELECT * FROM documents WHERE id=?",id);
            if (docs.isEmpty()) throw new ServiceException(ServiceException.Code.NOT_FOUND,"Document not found.");
            var doc=docs.getFirst(); access(u,requests.require(c,(UUID)doc.get("request_id"))); return doc;
        });
    }
    public InputStream openDownload(SessionManager.Session session,Grant grant) throws IOException {
        Actor actor=verified(session); Download download=downloads.get(grant.transferId());
        if (download==null || download.session()!=session || !download.grant().equals(grant)) throw ServiceException.forbidden();
        var doc=document(actor,download.documentId());
        if (grant.direction()!=Direction.DOWNLOAD || grant.totalBytes()!=((Number)doc.get("size_bytes")).longValue()) throw ServiceException.forbidden();
        Path path=root.resolve((String)doc.get("storage_name")).normalize();
        if (!path.getParent().equals(root) || Files.isSymbolicLink(path)) throw new IOException("Invalid storage destination.");
        if (Files.size(path)!=grant.totalBytes()) throw new IOException("Stored file size mismatch.");
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try (InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer=new byte[65536]; int n; while ((n=in.read(buffer))!=-1) digest.update(buffer,0,n);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(doc.get("sha256"))) throw new IOException("Stored file integrity check failed.");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        return new FilterInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            @Override public void close() throws IOException {
                try { super.close(); } finally { downloads.remove(grant.transferId(),download); }
            }
        };
    }
    @Override public Optional<Message> completion(SessionManager.Session session,UUID transferId) {
        Actor actor=verified(session); var doc=document(actor,transferId);
        var payload=JsonNodeFactory.instance.objectNode().put("status","STORED").put("transferId",transferId.toString())
                .put("documentId",transferId.toString()).put("sha256",(String)doc.get("sha256"));
        return Optional.of(new Message(MessageType.FILE_META,payload));
    }
    @Override public InputStream download(SessionManager.Session session,Grant grant) throws IOException { return openDownload(session,grant); }
}
