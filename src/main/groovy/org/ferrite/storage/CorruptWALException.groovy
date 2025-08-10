package org.ferrite.storage

import groovy.transform.CompileStatic

@CompileStatic
class CorruptWALException extends RuntimeException {
    CorruptWALException(String message) {
        super(message)
    }

    CorruptWALException(String message, Throwable cause) {
        super(message, cause)
    }
}
