package org.ferrite.storage

import groovy.transform.CompileStatic

@CompileStatic
class CorruptSegmentException extends RuntimeException {
    CorruptSegmentException(String message) {
        super(message)
    }

    CorruptSegmentException(String message, Throwable cause) {
        super(message, cause)
    }
}
