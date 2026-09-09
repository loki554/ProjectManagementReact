package com.pmtracker.project_management_backend.common.exception;

public class TooManyRealtimeStreamsException extends RuntimeException {

    public TooManyRealtimeStreamsException() {
        super("Too many open realtime streams for this user");
    }
}
