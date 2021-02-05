## Minimal reproducer for JDK-8261235

This Maven project contains a minimal reproducer for the C1 crash reported in
https://bugs.openjdk.java.net/browse/JDK-8261235

### Running

Point your JAVA_HOME to a fastdebug build and run the JDK8261235Reproducer main method:

    export JAVA_HOME=/Path/To/OpenJDK/FastDebugBuild

    mvn compile exec:java -Dexec.mainClass="com.github.eirbjo.JDK8261235Reproducer"

### Result

You should observe a crash similar to this:


    # To suppress the following error report, specify this argument
    # after -XX: or in .hotspotrc:  SuppressErrorAt=/c1_LIR.hpp:732
    #
    # A fatal error has been detected by the Java Runtime Environment:
    #
    #  Internal Error (/Users/eirbjo/Projects/ext/openjdk/github/jdk/src/hotspot/share/c1/c1_LIR.hpp:732), pid=7921, tid=41987
    #  assert(res->vreg_number() == index) failed: conversion check

