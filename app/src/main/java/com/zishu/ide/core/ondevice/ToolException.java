package com.zishu.ide.core.ondevice;

/** A build tool (aapt2, ECJ, D8, signer) failed. The message is shown to the user as the failure title. */
public final class ToolException extends Exception {
    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
