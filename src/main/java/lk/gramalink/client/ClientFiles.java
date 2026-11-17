package lk.gramalink.client;

import lk.gramalink.shared.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One bounded transfer at a time on the authenticated socket. Never retries an uncertain upload. */
final class ClientFiles implements AutoCloseable {
    private final NetworkClient client;
    private final FileTransferProgress progress;
    private final LongSupplier generation;
    private final BooleanSupplier authorized;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"gramalink-files");t.setDaemon(true);return t;});
    private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Transfer transfer;
    /** An explicit backend refusal is not a broken TCP session or an uncertain commit. */
    private static final class TransferRejected extends IOException {
        TransferRejected(String message) { super(message); }
    }
    static boolean uploadsAllowed(String status) {
        return status != null && !Set.of("APPROVED", "REJECTED").contains(status);
    }
    private static final class Transfer {
        final long epoch; final String correlation=UUID.randomUUID().toString(); final String sha; final long size;
        final FileTransferProgress.Direction direction;
        final CompletableFuture<Message> ready=new CompletableFuture<>(), stored=new CompletableFuture<>();
        final BlockingQueue<FileChunk> chunks=new ArrayBlockingQueue<>(32);
        volatile CompletableFuture<Message> ack; volatile UUID id;
        Transfer(long epoch,String sha,long size,FileTransferProgress.Direction direction){this.epoch=epoch;this.sha=sha;this.size=size;this.direction=direction;}
        void fail(Throwable error){ready.completeExceptionally(error);stored.completeExceptionally(error);if(ack!=null)ack.completeExceptionally(error);}
    }
    ClientFiles(NetworkClient client,FileTransferProgress progress,LongSupplier generation,BooleanSupplier authorized){
        this.client=client;this.progress=progress;this.generation=generation;this.authorized=authorized;
    }
    private void current(long epoch) throws IOException {
        if(!authorized.getAsBoolean() || generation.getAsLong()!=epoch || !client.isConnected()) throw new IOException("File session ended. Sign in and reload documents; do not blindly repeat an upload.");
    }
    void control(Message message) throws IOException {
        Transfer t=transfer; if(t==null || generation.getAsLong()!=t.epoch || !authorized.getAsBoolean())return;
        if(message.type()==MessageType.FILE_META && "READY".equals(message.payload().path("status").asText()) && t.correlation.equals(message.correlationId())){
            try {t.id=UUID.fromString(message.payload().path("transferId").asText());}catch(IllegalArgumentException e){throw new IOException("Invalid transfer ID.",e);}
            t.ready.complete(message);
        } else if(message.type()==MessageType.FILE_META && "STORED".equals(message.payload().path("status").asText()) && t.id!=null
                && t.id.toString().equals(message.payload().path("transferId").asText())){
            if(t.direction!=FileTransferProgress.Direction.UPLOAD || !t.id.toString().equals(message.payload().path("documentId").asText())
                    || !t.sha.equals(message.payload().path("sha256").asText())) throw new IOException("Stored-document confirmation did not match the upload.");
            t.stored.complete(message);
        } else if(message.type()==MessageType.ACK && t.id!=null && t.id.toString().equals(message.payload().path("transferId").asText()) && t.ack!=null){
            t.ack.complete(message); // FileTransferProgress has already validated sequence, totals and final flags.
        } else if(message.type()==MessageType.ERROR && (t.correlation.equals(message.correlationId()) || t.id!=null && t.id.toString().equals(message.payload().path("transferId").asText()))){
            String code=message.payload().path("code").asText();
            t.fail(message.token()==null && t.id!=null && t.direction==FileTransferProgress.Direction.UPLOAD && "FILE_TRANSFER_FAILED".equals(code)
                    ? new TransferRejected("Upload rejected before STORED. No document was confirmed stored. " + LoginService.describe(message))
                    : new IOException(LoginService.describe(message)));
        }
    }
    void chunk(FileChunk chunk) throws IOException {
        Transfer t=transfer;
        if(t==null || t.direction!=FileTransferProgress.Direction.DOWNLOAD || !chunk.transferId().equals(t.id)) throw new IOException("Unrequested document bytes rejected.");
        current(t.epoch);
        try {if(!t.chunks.offer(chunk,100,TimeUnit.MILLISECONDS))throw new IOException("Local download consumer could not keep up. Transfer stopped safely.");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Download interrupted.",e);}
    }
    CompletableFuture<String> upload(String requestId,Path path){
        return work(()->{
            long epoch=generation.getAsLong();current(epoch);
            if(path==null || !Files.isRegularFile(path) || !Files.isReadable(path) || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))throw new IOException("Choose a readable PDF document.");
            long size=Files.size(path),limit=Long.getLong("gramalink.client.maxFileBytes",10485760L);
            if(size<1 || size>limit)throw new IOException("PDF exceeds the configured client byte limit ("+limit+"). The server makes the final validation decision.");
            var queries=new BackendQueries(client,()->{try{current(epoch);}catch(IOException e){throw new CompletionException(e);}});
            var request=queries.call(MessageType.GET_REQUEST,BackendQueries.object().put("requestId",requestId));
            if(!requestId.equals(BackendQueries.text(request,"id")))throw new IOException("Request authorization response did not match the selected request.");
            if(!uploadsAllowed(BackendQueries.text(request,"status")))
                throw new IOException("Uploads are not permitted for APPROVED or REJECTED requests. No file bytes were sent; existing documents remain available for download.");
            String sha=hash(path);
            // Recover a previous committed upload before negotiating another one. Never replay uncertain writes.
            for(var document:queries.pages(MessageType.LIST_DOCUMENTS,BackendQueries.object().put("requestId",requestId),100)) {
                BackendQueries.sameRequest(document,requestId);
                if(sha.equals(document.path("sha256").asText()) && document.path("size_bytes").isIntegralNumber()
                        && size==document.path("size_bytes").longValue() && path.getFileName().toString().equals(document.path("original_name").asText())) {
                    String id=BackendQueries.text(document,"id");
                    try { UUID.fromString(id); } catch(IllegalArgumentException e) { throw new IOException("Invalid stored-document ID.",e); }
                    return id;
                }
            }
            current(epoch);
            var t=new Transfer(epoch,sha,size,FileTransferProgress.Direction.UPLOAD);transfer=t;
            negotiate(t,BackendQueries.object().put("direction","UPLOAD").put("requestId",requestId).put("fileName",path.getFileName().toString()).put("mediaType","application/pdf").put("totalBytes",size).put("sha256",sha));
            long sent=0,sequence=0;
            try(InputStream in=Files.newInputStream(path)){
                while(sent<size){
                    current(epoch);byte[] bytes=in.readNBytes((int)Math.min(FileChunk.MAX_CHUNK_BYTES,size-sent));
                    if(bytes.length==0)throw new IOException("The selected document changed while uploading.");
                    sent+=bytes.length;t.ack=new CompletableFuture<>();var chunk=new FileChunk(t.id,sequence++,sent==size,bytes);
                    progress.markUploadChunk(epoch,chunk);client.sendChunk(chunk);t.ack.get(15,TimeUnit.SECONDS);
                }
                if(in.read()!=-1)throw new IOException("The selected document changed while uploading.");
            }
            Message stored=t.stored.get(20,TimeUnit.SECONDS);current(epoch);
            return stored.payload().path("documentId").asText();
        });
    }
    CompletableFuture<Path> download(String requestId,String documentId,Path destination){
        return work(()->{
            long epoch=generation.getAsLong();current(epoch);
            if(destination==null || Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("Choose a new destination; existing files are never overwritten.");
            var queries=new BackendQueries(client,()->{try{current(epoch);}catch(IOException e){throw new CompletionException(e);}});
            var metadata=queries.pages(MessageType.LIST_DOCUMENTS,BackendQueries.object().put("requestId",requestId),100).stream()
                    .filter(d->documentId.equals(d.path("id").asText())).findFirst().orElseThrow(()->new IOException("Document is not in the authorized request document list."));
            BackendQueries.sameRequest(metadata,requestId);
            long size=metadata.path("size_bytes").longValue();String sha=BackendQueries.text(metadata,"sha256");
            if(!metadata.path("size_bytes").isIntegralNumber() || size<1 || size>Long.getLong("gramalink.client.maxFileBytes",10485760L) || !sha.matches("[a-f0-9]{64}"))throw new IOException("Invalid or oversized document metadata.");
            var t=new Transfer(epoch,sha,size,FileTransferProgress.Direction.DOWNLOAD);transfer=t;
            Path target=destination.toAbsolutePath().normalize();
            negotiate(t,BackendQueries.object().put("direction","DOWNLOAD").put("documentId",documentId));
            Path partial=Files.createTempFile(target.getParent(),".gramalink-download-",".part");
            long received=0;
            try(OutputStream out=Files.newOutputStream(partial,StandardOpenOption.WRITE)){
                while(received<size){current(epoch);var chunk=t.chunks.poll(15,TimeUnit.SECONDS);if(chunk==null)throw new IOException("Download stalled. Incomplete .part file retained; no completed file published.");out.write(chunk.data());received+=chunk.data().length;}
            }
            current(epoch);
            if(!hash(partial).equals(sha))throw new IOException("Downloaded checksum failed. Incomplete .part file retained.");
            Files.move(partial,target); // Same-directory move, no overwrite and no server-provided local path.
            return target;
        });
    }
    private void negotiate(Transfer t,com.fasterxml.jackson.databind.node.ObjectNode payload) throws Exception {
        current(t.epoch);progress.expectReady(t.epoch,t.correlation,t.direction,t.size);
        var reply=client.request(new Message(MessageType.FILE_META,t.correlation,null,payload),Duration.ofSeconds(10)).get(12,TimeUnit.SECONDS);
        if(reply.token()!=null || !t.correlation.equals(reply.correlationId())) throw new IOException("Invalid file authorization response.");
        if(reply.type()==MessageType.ERROR) {
            String code=reply.payload().path("code").asText();
            if(!reply.payload().has("transferId") && Set.of("FILE_TRANSFER_REJECTED","TRANSFER_LIMIT","NOT_IMPLEMENTED").contains(code))
                throw new TransferRejected("Transfer rejected before READY. No file bytes were sent. " + LoginService.describe(reply));
            throw new IOException(LoginService.describe(reply));
        }
        t.ready.get(12,TimeUnit.SECONDS);current(t.epoch);
    }
    private interface Work<T>{T run() throws Exception;}
    private <T> CompletableFuture<T> work(Work<T> operation){
        if(!running.compareAndSet(false,true))return CompletableFuture.failedFuture(new IllegalStateException("Wait for the current file transfer."));
        try{return CompletableFuture.supplyAsync(()->{
            try{return operation.run();}
            catch(Exception e){
                Throwable cause=e;
                while((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause()!=null)cause=cause.getCause();
                Transfer t=transfer;
                if(t!=null){
                    t.fail(cause);
                    String detail=cause instanceof TransferRejected ? cause.getMessage() : "Transfer incomplete. Reload LIST_DOCUMENTS before retrying; storage is unconfirmed.";
                    if(t.id==null)progress.failNegotiation(t.epoch,t.correlation,detail);
                    else progress.failTransfer(t.epoch,t.id,detail);
                    // Unknown framing/timeout outcomes still require a fresh connection; a refusal does not.
                    if(!(cause instanceof TransferRejected))client.disconnectForRecovery();
                }
                throw new CompletionException(cause);
            }
            finally{transfer=null;running.set(false);}
        },worker);}catch(RejectedExecutionException e){running.set(false);return CompletableFuture.failedFuture(e);}
    }
    static String hash(Path path) throws IOException {
        try{var digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] buffer=new byte[FileChunk.MAX_CHUNK_BYTES];int count;while((count=in.read(buffer))!=-1)digest.update(buffer,0,count);}return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    void disconnected(){Transfer t=transfer;if(t!=null)t.fail(new IOException("Connection lost. Reload documents after fresh authentication; no upload is replayed."));}
    @Override public void close(){disconnected();worker.shutdownNow();}
}
