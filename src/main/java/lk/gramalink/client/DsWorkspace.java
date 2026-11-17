package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Package-local view port; DsGateway is the only production implementation. */
interface DsWorkspace {
    void requireDsOfficer();
    boolean isDsAuthorized();
    Optional<DsData.Identity> identity();
    boolean supports(DsData.ActionKind kind);
    boolean requiresVersion(DsData.ActionKind kind);
    void setListener(DsGateway.Listener listener);
    CompletableFuture<Message> perform(DsData.Action action);
    CompletableFuture<Void> disconnect();
}
