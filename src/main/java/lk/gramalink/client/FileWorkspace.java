package lk.gramalink.client;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Shared document UI port. Preview implementations inherit unavailable submissions. */
interface FileWorkspace {
    default boolean supportsFiles(){return false;}
    default CompletableFuture<String> upload(String requestId,Path path){return CompletableFuture.failedFuture(new UnsupportedOperationException("Authorized file integration unavailable."));}
    default CompletableFuture<Path> download(String requestId,String documentId,Path destination){return CompletableFuture.failedFuture(new UnsupportedOperationException("Authorized file integration unavailable."));}
}
