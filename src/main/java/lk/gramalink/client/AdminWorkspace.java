package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Package-local view port. AdminGateway is the only production implementation. */
interface AdminWorkspace {
    void requireAdministrator();
    boolean isAdministratorAuthorized();
    Optional<AdminData.Identity> identity();
    boolean supports(AdminData.Operation operation);
    void setListener(AdminGateway.Listener listener);
    CompletableFuture<Message> perform(AdminData.Command command);
    CompletableFuture<Void> disconnect();
}
