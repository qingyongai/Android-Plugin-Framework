package com.plugin.content;


import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.plugin.core.PluginContextTheme;
import com.plugin.core.PluginCreator;
import com.plugin.core.PluginInjector;
import com.plugin.core.PluginLoader;
import com.plugin.core.app.ActivityThread;
import com.plugin.core.localservice.LocalServiceManager;
import com.plugin.core.manager.PluginActivityMonitor;
import com.plugin.core.systemservice.AndroidWebkitWebViewFactoryProvider;
import com.plugin.util.LogUtil;
import com.plugin.util.RefInvoker;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import dalvik.system.DexClassLoader;

/**
 * <Pre>
 * @author cailiming
 * </Pre>
 *
 */
public class PluginRuntime implements Serializable {

	private static PluginRuntime runtime;

	private HashMap<String, LoadedPlugin> loadedPluginMap = new HashMap<String, LoadedPlugin>();

	private PluginRuntime() {
		checkIfWithPluginProcess();
	}

	private void checkIfWithPluginProcess() {

	}

	public static PluginRuntime instance() {
		if (runtime == null) {
			synchronized (PluginRuntime.class) {
				if (runtime == null) {
					runtime = new PluginRuntime();
				}
			}
		}
		return runtime;
	}

	public LoadedPlugin getRunningPlugin(String packageName) {
		return loadedPluginMap.get(packageName);
	}

	public LoadedPlugin startPlugin(String packageName) {
		LoadedPlugin plugin = loadedPluginMap.get(packageName);

		if (plugin == null) {
			LogUtil.e("正在初始化插件 " + packageName + ": Resources, DexClassLoader, Context, Application");

			PluginDescriptor pluginDescriptor = PluginLoader.getPluginDescriptorByPluginId(packageName);

			Resources pluginRes = PluginCreator.createPluginResource(
					PluginLoader.getApplicatoin().getApplicationInfo().sourceDir,
					PluginLoader.getApplicatoin().getResources(), pluginDescriptor);

			DexClassLoader pluginClassLoader = PluginCreator.createPluginClassLoader(
							pluginDescriptor.getInstalledPath(),
							pluginDescriptor.isStandalone(),
							pluginDescriptor.getDependencies(),
							pluginDescriptor.getMuliDexList());

			Context pluginContext = PluginCreator.createPluginContext(
					pluginDescriptor,
					PluginLoader.getApplicatoin().getBaseContext(),
					pluginRes,
					pluginClassLoader);

			//插件Context默认主题设置为插件application主题
			pluginContext.setTheme(pluginDescriptor.getApplicationTheme());

			plugin = new LoadedPlugin(packageName,
					pluginDescriptor.getInstalledPath(),
					pluginContext,
					pluginClassLoader);

			loadedPluginMap.put(packageName, plugin);

			Application pluginApplication = callPluginApplicationOnCreate(pluginContext, pluginClassLoader, pluginDescriptor);

			((PluginContextTheme)pluginContext).setPluginApplication(pluginApplication);

			plugin.pluginApplication = pluginApplication;//这里之所以不放在LoadedPlugin的构造器里面，是因为contentprovider在安装时loadclass，造成死循环

			try {
				ActivityThread.installPackageInfo(PluginLoader.getApplicatoin(), packageName, pluginDescriptor,
						pluginClassLoader, pluginRes, pluginApplication);
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}

			LogUtil.e("初始化插件" + packageName + "完成");
		} else {
			//LogUtil.d("IS RUNNING", packageName);
		}

		return plugin;
	}

	private Application callPluginApplicationOnCreate(Context pluginContext, DexClassLoader classLoader, PluginDescriptor pluginDescriptor) {

		Application application = null;

		try {
			LogUtil.d("创建插件Application", pluginDescriptor.getApplicationName());
			//阻止自动安装multidex
			try {
				Class mulitDex = classLoader.loadClass("android.support.multidex.MultiDex");
				RefInvoker.setFieldObject(null, mulitDex, "IS_VM_MULTIDEX_CAPABLE", true);
			} catch (Exception e) {
			}
			application = Instrumentation.newApplication(classLoader.loadClass(pluginDescriptor.getApplicationName()),
					pluginContext);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} 	catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}

		//安装ContentProvider, 在插件Application对象构造以后，oncreate调用之前
		PluginInjector.installContentProviders(PluginLoader.getApplicatoin(), pluginDescriptor.getProviderInfos().values());

		//执行onCreate
		if (application != null) {

			//先拿到宿主的crashHandler
			Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();

			application.onCreate();

			// 再还原宿主的crashHandler，这里之所以需要还原CrashHandler，
			// 是因为如果插件中自己设置了自己的crashHandler（通常是在oncreate中），
			// 会导致当前进程的主线程的handler被意外修改。
			// 如果有多个插件都有设置自己的crashHandler，也会导致混乱
			// 所以这里直接屏蔽掉插件的crashHandler
			//TODO 或许也可以做成消息链进行分发？
			Thread.setDefaultUncaughtExceptionHandler(old);
		}

		return application;
	}

	public void stopPlugin(String packageName, PluginDescriptor pluginDescriptor) {

		LoadedPlugin plugin = getRunningPlugin(packageName);

		if (plugin == null) {
			return;
		}
		//
		//退出WebView, LocalService、Activity、BroadcastReceiver、LocalBroadcastManager, Service、AssetManager、ContentProvider、fragment
		//

		//退出webview
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				AndroidWebkitWebViewFactoryProvider.switchWebViewContext(PluginLoader.getApplicatoin());
			}
		});

		//退出LocalService
		LocalServiceManager.unRegistService(pluginDescriptor);

		//退出Activity
		PluginLoader.getApplicatoin().sendBroadcast(new Intent(plugin.pluginPackageName + PluginActivityMonitor.ACTION_UN_INSTALL_PLUGIN));

		//退出BroadcastReceiver
		//广播一般有个注册方式
		//1、activity注册
		//		这种方式，在上一步Activitiy退出时会退出，所以不用处理
		//2、application注册
		//      这里需要处理这种方式注册的广播，这种方式注册的广播会被PluginContextTheme对象记录下来

		((PluginContextTheme) plugin.pluginApplication.getBaseContext()).unregisterAllReceiver();

		//退出 LocalBroadcastManager
		Object mInstance = RefInvoker.getStaticFieldObject("android.support.v4.content.LocalBroadcastManager", "mInstance");
		if (mInstance != null) {
			HashMap<BroadcastReceiver, ArrayList<IntentFilter>> mReceivers = (HashMap<BroadcastReceiver, ArrayList<IntentFilter>>)RefInvoker.getFieldObject(mInstance,
					"android.support.v4.content.LocalBroadcastManager", "mReceivers");
			if (mReceivers != null) {
				Iterator<BroadcastReceiver> ir = mReceivers.keySet().iterator();
				while(ir.hasNext()) {
					BroadcastReceiver item = ir.next();
					if (item.getClass().getClassLoader() == plugin.pluginClassLoader) {
						RefInvoker.invokeMethod(mInstance, "android.support.v4.content.LocalBroadcastManager",
								"unregisterReceiver", new Class[]{BroadcastReceiver.class}, new Object[]{item});
					}
				}
			}
		}

		//退出Service
		//bindservie启动的service应该不需要处理，退出activity的时候会unbind
		Map<IBinder, Service> map = ActivityThread.getAllServices();
		if (map != null) {
			Collection<Service> list = map.values();
			for (Service s :list) {
				if (s.getClass().getClassLoader() == plugin.pluginClassLoader) {
					s.stopSelf();
				}
			}
		}

		//退出AssetManager
		//pluginDescriptor.getPluginContext().getResources().getAssets().close();

		//退出ContentProvider
		//TODO ContentProvider如何退出？
		//ActivityThread.releaseProvider(IContentProvider provider, boolean stable)

		//退出fragment
		//即退出由FragmentManager保存的Fragment
		//TODO fragment如何退出？

		loadedPluginMap.remove(packageName);
	}

	public boolean isRunning(String packageName) {
		return loadedPluginMap.get(packageName) != null;
	}

}