package org.kathra.sourcemanager.controller;

import org.apache.camel.builder.RouteBuilder;
import org.kathra.sourcemanager.Config;

public class GenerateTokenScheduler extends RouteBuilder {

    @Override
    public void configure() {
        Config config = new Config();
        from("scheduler://foo?delay="+config.getDelaySchedule()+"").process(GitlabGenerateToken.getInstance()).to("mock:success");
    }

}