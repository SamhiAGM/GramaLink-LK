package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Package-local view port. CitizenGateway is the only production implementation. */
interface CitizenWorkspace extends FileWorkspace {
    void requireCitizen();
    boolean isCitizenAuthorized();
    Optional<CitizenData.Identity> identity();
    boolean supports(CitizenProtocol.Operation operation);
    void setListener(CitizenGateway.Listener listener);
    default void refresh() { }
    default boolean usesRegisteredDivision() { return false; }
    default boolean supportsCorrections() { return false; }
    default CompletableFuture<Message> resubmit(String requestId,Long version,String title,String description) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Correction submission unavailable."));
    }
    CompletableFuture<Message> createRequest(CitizenData.Draft draft);
    CompletableFuture<Message> sendMessage(String requestId, String text);
    CompletableFuture<CitizenData.Attachment> inspectAttachment(Path path);
    CompletableFuture<Void> disconnect();
}
