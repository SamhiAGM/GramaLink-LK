package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Package-local view port. GnGateway is the only production implementation. */
interface GnWorkspace {
    void requireGnOfficer();
    boolean isGnAuthorized();
    Optional<GnData.Identity> identity();
    boolean supports(GnData.ActionKind kind);
    void setListener(GnGateway.Listener listener);
    CompletableFuture<Message> perform(GnData.Action action);
    CompletableFuture<Void> disconnect();
}
