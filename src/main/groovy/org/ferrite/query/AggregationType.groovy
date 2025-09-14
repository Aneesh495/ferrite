package org.ferrite.query

import groovy.transform.CompileStatic

@CompileStatic
enum AggregationType {
    SUM,
    COUNT,
    MIN,
    MAX,
    AVG,
    STDDEV,
    LAST
}
