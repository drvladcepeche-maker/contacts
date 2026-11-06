package com.mycontacts;

interface IApkInstallService {
    void destroy() = 16777114;
    String installApk(String apkPath, String expectedSha256) = 1;
}
