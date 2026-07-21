package priv.kit.sample.userservice;

interface IPrivilegeSampleDedicatedUserService {
    void destroy() = 16777114;
    String describe(String label) = 1;
    int getUid() = 2;
    int getPid() = 3;
    int getCallCount() = 4;
    String getMode() = 5;
}
