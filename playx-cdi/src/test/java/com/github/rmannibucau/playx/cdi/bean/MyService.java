package com.github.rmannibucau.playx.cdi.bean;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MyService {
    public String test() {
        return "ok";
    }
}
