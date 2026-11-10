package lk.gramalink.shared;

/** Message names shared by the client and server protocol. */
public enum MessageType {
    LOGIN,
    LOGIN_OK,
    CREATE_REQUEST,
    NEW_REQUEST,
    STATUS_UPDATE,
    REQUEST_UPDATED,
    CHAT_MESSAGE,
    NOTIFICATION,
    FILE_META,
    FILE_CHUNK,
    HEARTBEAT,
    ACK,
    ERROR
}
