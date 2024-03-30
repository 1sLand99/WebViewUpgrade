package com.norman.webviewup.lib.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.norman.webviewup.lib.reflect.RuntimeAccess;
import com.norman.webviewup.lib.service.binder.BinderHook;
import com.norman.webviewup.lib.service.interfaces.IActivityThread;
import com.norman.webviewup.lib.service.interfaces.IApplicationInfo;
import com.norman.webviewup.lib.service.interfaces.IContextImpl;
import com.norman.webviewup.lib.service.interfaces.IPackageManager;
import com.norman.webviewup.lib.service.interfaces.IServiceManager;
import com.norman.webviewup.lib.service.binder.ProxyBinder;
import com.norman.webviewup.lib.service.proxy.PackageManagerProxy;
import com.norman.webviewup.lib.util.ProcessUtils;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PackageManagerServiceHook extends BinderHook {

    private final Context context;

    private final String webViewPackageName;

    private final String apkPath;

    private Map<String, IBinder> binderCacheMap;


    public PackageManagerServiceHook(@NonNull Context context,
                                     @NonNull String packageName,
                                     @NonNull String apkPath) {
        this.context = context;
        this.webViewPackageName = packageName;
        this.apkPath = apkPath;
    }

    private final PackageManagerProxy proxy = new PackageManagerProxy() {

        @Override
        protected PackageInfo getPackageInfo(String packageName, long flags, int userId) {
            return getPackageInfo(packageName, (int) flags);
        }

        @Override
        protected PackageInfo getPackageInfo(String packageName, int flags, int userId) {
            return getPackageInfo(packageName, flags);
        }

        @Override
        protected int getComponentEnabledSetting(ComponentName componentName, int userId) {
            return getComponentEnabledSetting(componentName);
        }

        @Override
        protected PackageInfo getPackageInfo(String packageName, int flags) {
            if (packageName.equals(webViewPackageName)) {
                PackageInfo packageInfo = context.getPackageManager().getPackageArchiveInfo(apkPath, flags);
                if (packageInfo == null) {
                    flags &= ~PackageManager.GET_SIGNATURES;
                    packageInfo = context.getPackageManager().getPackageArchiveInfo(apkPath, flags);
                }
                boolean is64Bit = ProcessUtils.is64Bit();
                String[] supportBitAbis = is64Bit ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
                Arrays.sort(supportBitAbis, Collections.reverseOrder());
                String nativeLibraryDir = null;
                String cpuAbi = null;
                ZipFile zipFile;
                try {
                    zipFile = new ZipFile(new File(apkPath));
                    Enumeration<? extends ZipEntry> entries = zipFile.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String entryName = entry.getName();
                        if (entryName.contains("../") || entry.isDirectory()) {
                            continue;
                        }
                        if (!entryName.startsWith("lib/") && !entryName.endsWith(".so")) {
                            continue;
                        }
                        String[] split = entry.getName().split("/");
                        if (split.length < 2) {
                            continue;
                        }
                        String abi = split[1];
                        if (Arrays.binarySearch(supportBitAbis, abi) >= 0) {
                            nativeLibraryDir = apkPath + "!/lib/" + abi;
                            cpuAbi = abi;
                            break;
                        }
                    }
                } catch (Throwable ignore) {

                }

                if (nativeLibraryDir == null) {
                    throw new NullPointerException("unable to find supported abis "
                            + Arrays.toString(supportBitAbis)
                            + " in apk " + apkPath);
                }
                try {
                    IApplicationInfo iApplicationInfo = RuntimeAccess.objectAccess(IApplicationInfo.class, packageInfo.applicationInfo);
                    iApplicationInfo.setPrimaryCpuAbi(cpuAbi);
                } catch (Throwable ignore) {

                }
                packageInfo.applicationInfo.nativeLibraryDir = nativeLibraryDir;
                if (TextUtils.isEmpty(packageInfo.applicationInfo.sourceDir)) {
                    packageInfo.applicationInfo.sourceDir = apkPath;
                }
                return packageInfo;
            }
            return (PackageInfo) invoke();
        }

        @Override
        protected int getComponentEnabledSetting(ComponentName componentName) {
            if (componentName.getPackageName().equals(webViewPackageName)) {
                return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            } else {
                return (int) invoke();
            }
        }

        @Override
        protected String getInstallerPackageName(String packageName) {
            if (packageName.equals(webViewPackageName)) {
                // fake google play
                return "com.android.vending";
            } else {
                return (String) invoke();
            }
        }

        @Override
        protected IBinder asBinder() {
            IBinder proxyBinder = getProxyBinder();
            return proxyBinder != null ? proxyBinder : (IBinder) invoke();
        }
    };


    @Override
    protected IBinder onTargetBinderObtain() {
        IServiceManager serviceManager = RuntimeAccess.staticAccess(IServiceManager.class);
        return serviceManager.getService(IPackageManager.SERVICE);
    }

    @Override
    protected ProxyBinder onProxyBinderCreate(IBinder binder) {
        IPackageManager service = RuntimeAccess.staticAccess(IPackageManager.class);
        IServiceManager serviceManager = RuntimeAccess.staticAccess(IServiceManager.class);

        IInterface targetInterface = service.asInterface(binder);
        proxy.setTarget(targetInterface);
        IInterface proxyInterface = (IInterface) proxy.get();
        ProxyBinder proxyBinder = new ProxyBinder(targetInterface, proxyInterface);

        binderCacheMap = serviceManager.getServiceCache();
        return proxyBinder;
    }

    @Override
    protected void onTargetBinderRestore(IBinder binder) {
        IInterface targetInterface;
        try {
            targetInterface = binder.queryLocalInterface(binder.getInterfaceDescriptor());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
        binderCacheMap.put(IPackageManager.SERVICE, binder);
        updateActivityThreadPackageManager(targetInterface);
        flushContextImplPackageManager();
    }

    @Override
    protected void onProxyBinderReplace(ProxyBinder binder) {
        binderCacheMap.put(IPackageManager.SERVICE, binder);
        updateActivityThreadPackageManager(binder.getProxyIInterface());
        flushContextImplPackageManager();
    }

    private static void updateActivityThreadPackageManager(IInterface iInterface) {
        IActivityThread activityThread = RuntimeAccess.staticAccess(IActivityThread.class);
        activityThread.setPackageManager(iInterface);
    }

    private void flushContextImplPackageManager() {
        Context baseContext = context.getApplicationContext();
        while (baseContext instanceof ContextWrapper) {
            baseContext = ((ContextWrapper) context).getBaseContext();
        }
        IContextImpl contextImpl = RuntimeAccess.objectAccess(IContextImpl.class, baseContext);
        contextImpl.setPackageManager(null);
    }


}