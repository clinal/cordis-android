package io.github.clinal.cordis.runtime;

interface IAndroidControlService {
    String execute(String command);
    void destroy();
}
