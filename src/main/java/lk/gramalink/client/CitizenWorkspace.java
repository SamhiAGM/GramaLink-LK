package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Package-local view port. CitizenGateway is the only production implementation. */
interface CitizenWorkspace {
    void requireCitizen();
    boolean isCitizenAuthorized();
    Optional<CitizenData.Identity> identity();
    boolean supports(CitizenProtocol.Operation operation);
    void setListener(CitizenGateway.Listener listener);
    CompletableFuture<Message> createRequest(CitizenData.Draft draft);
    CompletableFuture<Message> sendMessage(String requestId, String text);
    CompletableFuture<CitizenData.Attachment> inspectAttachment(Path path);
    CompletableFuture<Void> disconnect();
}
