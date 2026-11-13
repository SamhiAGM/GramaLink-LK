package lk.gramalink.server.backend;

public final class ServiceException extends RuntimeException {
    public enum Code { VALIDATION_ERROR, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, INVALID_TRANSITION, CONFLICT, INTERNAL_ERROR }
    private final Code code;
    public ServiceException(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
    public static ServiceException invalid(String message) { return new ServiceException(Code.VALIDATION_ERROR, message); }
    public static ServiceException forbidden() { return new ServiceException(Code.FORBIDDEN, "This action is not permitted."); }
}
