package com.example;

import org.jetbrains.annotations.NotNull;

/** Code that is syntactically broken: IntelliJ still formats around error elements. */
public class Broken {
    void method( {
        int x = ;
        if (x > ) { System.out.println("unterminated
    }

    @NotNull String missingBody()

    class Inner extends { }
