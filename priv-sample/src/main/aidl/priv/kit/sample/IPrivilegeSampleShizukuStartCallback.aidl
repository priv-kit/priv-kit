package priv.kit.sample;

interface IPrivilegeSampleShizukuStartCallback {
    void onOutput(String source, String message) = 1;
    void onFinished(int exitCode, String output) = 2;
    void onFailure(String message, String detail) = 3;
}
