package priv.kit.sample;

import priv.kit.sample.IPrivilegeSampleShizukuStartCallback;

interface IPrivilegeSampleShizukuStartService {
    void startWithCallback(String commandLine, IPrivilegeSampleShizukuStartCallback callback) = 1;
    void destroy() = 16777114;
}
