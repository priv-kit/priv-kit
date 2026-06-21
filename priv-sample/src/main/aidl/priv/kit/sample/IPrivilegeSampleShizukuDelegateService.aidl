package priv.kit.sample;

interface IPrivilegeSampleShizukuDelegateService {
    String start(String commandLine) = 1;
    boolean isLaunchProcessAlive() = 2;
    String getLaunchOutput() = 3;
    void stopLaunchProcess() = 4;
    void destroy() = 16777114;
}
