package priv.kit.sample;

interface IPrivilegeSampleEmbeddedUserService {
    String describe(String label);
    int getUid();
    int getPid();
    int getCallCount();
    String getMode();
}
