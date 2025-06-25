package org.ferrite.common

import spock.lang.Specification

class TagSetSpec extends Specification {

    def "should sort tags canonically regardless of input ordering"() {
        given:
        def t1 = TagSet.of([region: "us-east-1", env: "prod", app: "payment"])
        def t2 = TagSet.of([app: "payment", region: "us-east-1", env: "prod"])

        expect:
        t1 == t2
        t1.hashCode() == t2.hashCode()
        t1.toCanonicalString() == "app=payment,env=prod,region=us-east-1"
        t2.toCanonicalString() == "app=payment,env=prod,region=us-east-1"
    }

    def "should perform fast binary search lookup"() {
        given:
        def tagSet = TagSet.of("host", "db-01", "dc", "us-central", "rack", "r12")

        expect:
        tagSet.size() == 3
        tagSet.get("host") == "db-01"
        tagSet.get("dc") == "us-central"
        tagSet.get("rack") == "r12"
        tagSet.get("nonexistent") == null
        tagSet.containsKey("host")
        !tagSet.containsKey("nonexistent")
    }

    def "should test matches against query filter"() {
        given:
        def tagSet = TagSet.of([host: "srv-99", role: "worker", env: "staging"])

        expect:
        tagSet.matches([role: "worker"])
        tagSet.matches([role: "worker", env: "staging"])
        !tagSet.matches([role: "worker", env: "prod"])
        !tagSet.matches([cluster: "k8s-prod"])
    }

    def "should support withTag and withoutTag immutably"() {
        given:
        def base = TagSet.of([k1: "v1", k2: "v2"])

        when:
        def updated = base.withTag("k3", "v3")
        def removed = base.withoutTag("k1")

        then:
        base.size() == 2
        updated.size() == 3
        updated.get("k3") == "v3"
        removed.size() == 1
        !removed.containsKey("k1")
    }
}
