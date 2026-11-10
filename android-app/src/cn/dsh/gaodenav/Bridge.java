package cn.dsh.gaodenav;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 小米手环互联桥（xms-wearable-lib），**纯反射**实现。
 *
 * 为什么用反射而不是直接 import：
 *   1. 手头只有 AAR（内含 classes.jar），本地没有 SDK 的源码/文档；
 *   2. BandQQ 是 Kotlin + AGP 编译，Task 泛型/SAM 在 Java 里怎么落地依赖具体 SDK 版本；
 *   3. 反射让本文件与 jars 解耦：只要运行时 classes.jar 被 dex 进 APK，API 名称对就能跑，
 *      猜错时是「UI 上显示具体失败原因」而不是装不上/闪退。
 *
 * 连接流程（与 BandQQ InterconnectBridge 一致）：
 *   Wearable.getNodeApi/getAuthApi/getMessageApi
 *     -> nodeApi.connectedNodes                   发现已配对节点
 *     -> authApi.checkPermissions(id,[DEVICE_MANAGER]) -> 未授权则 requestPermission
 *     -> nodeApi.launchWearApp(id,"/pages/index")  拉起手环端快应用
 *     -> messageApi.addListener(id, listener)      必须注册，否则通道可能不建立
 *     -> messageApi.sendMessage(id, json bytes)    下发导航帧
 *
 * 签名语义：运动健康/手环会拿 APK 证书指纹与手环端 rpk 证书指纹比对，
 * 不一致抛 SignatureVerifyFailedException（message 含 "fingerprint verify failed"）。
 */
public final class Bridge {

    private static final String TAG = "GaoDeNavBridge";
    public static final String WEAR_ENTRY_ROUTE = "/pages/index";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final Bridge INSTANCE = new Bridge();

    public static Bridge get() {
        return INSTANCE;
    }

    private Bridge() {
    }

    /** 异步回调：成功时 result 为结果、error=null；失败时反之 */
    public interface Callback {
        void done(Object result, Throwable error);
    }

    /** 反射参数类型过滤器 */
    private interface TypeCheck {
        boolean ok(Class<?> type);
    }

    private static final TypeCheck IS_STRING = new TypeCheck() {
        public boolean ok(Class<?> type) {
            return type == String.class || CharSequence.class.isAssignableFrom(type);
        }
    };

    private static final TypeCheck IS_BYTE_ARRAY = new TypeCheck() {
        public boolean ok(Class<?> type) {
            return type == byte[].class
                    || (type.isArray() && type.getComponentType() == Byte.TYPE)
                    || type == String.class;
        }
    };

    // ---------------------------------------------------------------- 状态

    private Context appContext;
    private Object nodeApi;
    private Object authApi;
    private Object messageApi;
    private Object deviceManagerPermission;

    private volatile String nodeId;
    private volatile boolean ready;
    private volatile String state = "未初始化";
    private volatile String lastError = "";
    private volatile int sentCount;
    private volatile int okCount;

    private final Object connectLock = new Object();
    private volatile boolean connecting;
    private volatile boolean listenerRegistered;
    /** 是否处于「签名不一致」状态（自动重连要退避到 60s，避免刷屏/反复拉起） */
    private volatile boolean signatureError;

    // ---------------------------------------------------------------- 初始化

    public synchronized boolean init(Context ctx) {
        if (ctx == null) {
            return false;
        }
        if (appContext == null) {
            appContext = ctx.getApplicationContext();
            if (appContext == null) {
                appContext = ctx;
            }
        }
        // 已经成功加载过就直接复用；加载失败过则允许重试（运动健康可能稍后才可用，
        // 不能一次失败就永久锁死）
        if (nodeApi != null && messageApi != null) {
            return true;
        }
        try {
            Class<?> wearable = Class.forName("com.xiaomi.xms.wearable.Wearable");
            nodeApi = callStatic(wearable, "getNodeApi", appContext);
            authApi = callStatic(wearable, "getAuthApi", appContext);
            messageApi = callStatic(wearable, "getMessageApi", appContext);
            deviceManagerPermission = permissionValue();
            boolean ok = nodeApi != null && messageApi != null;
            setState(ok ? "SDK 就绪" : "SDK 初始化不完整");
            Repo.log("互联 SDK " + (ok ? "加载成功" : "加载不完整")
                    + " nodeApi=" + (nodeApi != null) + " messageApi=" + (messageApi != null));
            return ok;
        } catch (Throwable t) {
            setError("加载互联 SDK 失败", t);
            setState("SDK 加载失败");
            return false;
        }
    }

    private static Object callStatic(Class<?> cls, String name, Object arg) throws Exception {
        Method m = cls.getMethod(name, Context.class);
        m.setAccessible(true);
        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
            return m.invoke(null, arg);
        }
        // Kotlin object / 单例写法：没有 @JvmStatic 时方法不是 static，走 INSTANCE 字段
        try {
            Field inst = cls.getField("INSTANCE");
            inst.setAccessible(true);
            Object target = inst.get(null);
            if (target != null) {
                return m.invoke(target, arg);
            }
        } catch (Throwable ignore) {
            // 继续往下抛原始信息
        }
        throw new IllegalStateException(name + " 既不是 static，也没有 INSTANCE 单例");
    }

    private Object permissionValue() {
        try {
            Class<?> p = Class.forName("com.xiaomi.xms.wearable.auth.Permission");
            Field f = p.getField("DEVICE_MANAGER");
            return f.get(null);
        } catch (Throwable t) {
            Repo.log("读取 Permission.DEVICE_MANAGER 失败（将跳过授权步骤）: " + shortError(t));
            return null;
        }
    }

    // ---------------------------------------------------------------- 连接

    /**
     * 建立/重建互联通道。
     *
     * @param launchApp 是否拉起手环端快应用。用户主动连接=true；后台自动重连=false，
     *                  避免用户退出手环应用后又被反复拉起。
     */
    public void connect(final boolean launchApp) {
        if (nodeApi == null && appContext != null) {
            init(appContext);
        }
        if (nodeApi == null) {
            Repo.log("connect: SDK 未初始化，无法连接");
            setState("SDK 未初始化");
            return;
        }
        synchronized (connectLock) {
            if (connecting) {
                Repo.log("connect: 正在连接中，忽略重复请求");
                return;
            }
            connecting = true;
        }
        setState("查找手环节点…");
        try {
            Method m = findMethod(nodeApi.getClass(), "connectedNodes", 0, (TypeCheck[]) null);
            if (m == null) {
                m = findMethod(nodeApi.getClass(), "getConnectedNodes", 0, (TypeCheck[]) null);
            }
            if (m == null) {
                fail("找不到 connectedNodes API（SDK 版本不符？）");
                return;
            }
            Object ret = m.invoke(nodeApi);
            onTask(ret, new Callback() {
                public void done(Object result, Throwable error) {
                    if (error != null) {
                        fail("connectedNodes 失败: " + shortError(error));
                        return;
                    }
                    List<Object> nodes = toList(result);
                    if (nodes.isEmpty()) {
                        setState("未发现手环（请先用小米运动健康连接手环）");
                        Repo.log(state);
                        finishConnect();
                        return;
                    }
                    Object node = nodes.get(0);
                    String id = nodeIdOf(node);
                    if (id == null || id.length() == 0) {
                        fail("手环节点 id 为空");
                        return;
                    }
                    nodeId = id;
                    setState("已发现手环节点");
                    Repo.log("发现手环节点 id=" + id);
                    authorize(launchApp);
                }
            });
        } catch (Throwable t) {
            fail("connect 异常: " + shortError(t));
        }
    }

    /** 2) 校验/申请 DEVICE_MANAGER 权限 */
    private void authorize(final boolean launchApp) {
        if (authApi == null || deviceManagerPermission == null) {
            Repo.log("跳过授权（authApi 或 DEVICE_MANAGER 不可用）");
            openWearApp(launchApp);
            return;
        }
        try {
            Object permArray = Array.newInstance(deviceManagerPermission.getClass(), 1);
            Array.set(permArray, 0, deviceManagerPermission);
            Method check = findMethod(authApi.getClass(), "checkPermissions", 2, IS_STRING, (TypeCheck) null);
            if (check == null) {
                Repo.log("找不到 checkPermissions API，直接尝试拉起");
                openWearApp(launchApp);
                return;
            }
            Object ret = check.invoke(authApi, nodeId, permArray);
            onTask(ret, new Callback() {
                public void done(Object result, Throwable error) {
                    if (error != null) {
                        if (isSignatureError(error)) {
                            failSignature("checkPermissions", error);
                            return;
                        }
                        Repo.log("checkPermissions 失败（继续尝试拉起）: " + shortError(error));
                        openWearApp(launchApp);
                        return;
                    }
                    boolean granted = firstBoolean(result, true);
                    Repo.log("DEVICE_MANAGER 已授权=" + granted);
                    if (granted) {
                        openWearApp(launchApp);
                    } else {
                        requestPermissionThenOpen(launchApp);
                    }
                }
            });
        } catch (Throwable t) {
            Repo.log("authorize 异常（继续尝试拉起）: " + shortError(t));
            openWearApp(launchApp);
        }
    }

    private void requestPermissionThenOpen(final boolean launchApp) {
        try {
            Method req = findMethod(authApi.getClass(), "requestPermission", 2, IS_STRING, (TypeCheck) null);
            if (req == null) {
                openWearApp(launchApp);
                return;
            }
            // requestPermission 声明是 (String, Permission...) 变长参数，
            // 反射调用变长方法时最后一项必须传数组，否则 IllegalArgumentException
            Object[] callArgs;
            if (req.isVarArgs()) {
                Class<?> comp = req.getParameterTypes()[1].getComponentType();
                Object arr = Array.newInstance(comp, 1);
                Array.set(arr, 0, deviceManagerPermission);
                callArgs = new Object[] {nodeId, arr};
            } else {
                callArgs = new Object[] {nodeId, deviceManagerPermission};
            }
            Object ret = req.invoke(authApi, callArgs);
            onTask(ret, new Callback() {
                public void done(Object result, Throwable error) {
                    if (error != null) {
                        Repo.log("requestPermission 报错（继续）: " + shortError(error));
                    } else {
                        Repo.log("已请求 DEVICE_MANAGER 授权 result=" + result
                                + "，需在小米运动健康里确认");
                    }
                    openWearApp(launchApp);
                }
            });
        } catch (Throwable t) {
            Repo.log("requestPermission 异常（继续）: " + shortError(t));
            openWearApp(launchApp);
        }
    }

    /** 3) 拉起手环端快应用 */
    private void openWearApp(final boolean launchApp) {
        if (!launchApp) {
            registerListener();
            return;
        }
        try {
            Method launch = findMethod(nodeApi.getClass(), "launchWearApp", 2, IS_STRING, (TypeCheck) null);
            if (launch == null) {
                Repo.log("找不到 launchWearApp API，直接注册监听");
                registerListener();
                return;
            }
            Object ret = launch.invoke(nodeApi, nodeId, WEAR_ENTRY_ROUTE);
            onTask(ret, new Callback() {
                public void done(Object result, Throwable error) {
                    if (error != null) {
                        Repo.log("拉起手环应用失败（手环端可能未安装/未更新 rpk）: "
                                + shortError(error));
                    } else {
                        Repo.log("已在手环上拉起 " + WEAR_ENTRY_ROUTE);
                    }
                    registerListener();
                }
            });
        } catch (Throwable t) {
            Repo.log("launchWearApp 异常: " + shortError(t));
            registerListener();
        }
    }

    /** 4) 注册下行监听（必须注册，否则互联通道可能不建立） */
    private void registerListener() {
        if (messageApi == null) {
            fail("messageApi 为空，无法注册监听");
            return;
        }
        try {
            Method add = findMethod(messageApi.getClass(), "addListener", 2, IS_STRING, (TypeCheck) null);
            if (add == null) {
                fail("找不到 addListener API");
                return;
            }
            Class<?> iface = add.getParameterTypes()[1];
            Object listener = makeMessageListener(iface);
            if (listener == null) {
                fail("无法创建 OnMessageReceivedListener 代理");
                return;
            }
            Object ret = add.invoke(messageApi, nodeId, listener);
            onTask(ret, new Callback() {
                public void done(Object result, Throwable error) {
                    if (error != null) {
                        String msg = String.valueOf(error).toLowerCase();
                        if (msg.contains("you have registered") || msg.contains("already registered")
                                || msg.contains("已注册")) {
                            Repo.log("监听已存在，视为注册成功");
                            markReady();
                        } else if (isSignatureError(error)) {
                            failSignature("addListener", error);
                        } else {
                            fail("注册监听失败: " + shortError(error));
                        }
                        return;
                    }
                    markReady();
                }
            });
        } catch (Throwable t) {
            fail("registerListener 异常: " + shortError(t));
        }
    }

    private void markReady() {
        ready = true;
        listenerRegistered = true;
        signatureError = false;
        setState("已连接手环");
        lastError = "";
        Repo.log("互联通道就绪");
        finishConnect();
    }

    private void finishConnect() {
        connecting = false;
    }

    // ---------------------------------------------------------------- 发送

    /** 下发一帧 JSON（调用方保证在线程外，不关心线程） */
    public boolean sendFrame(String json) {
        if (json == null) {
            return false;
        }
        if (messageApi == null) {
            Repo.log("发送失败：messageApi 为空");
            setState("SDK 未初始化");
            return false;
        }
        if (nodeId == null) {
            Repo.log("发送失败：尚未发现手环节点");
            setState("未连接手环");
            return false;
        }
        final byte[] bytes = json.getBytes(UTF8);
        try {
            Method send = findMethod(messageApi.getClass(), "sendMessage", 2, IS_STRING, IS_BYTE_ARRAY);
            if (send == null) {
                Repo.log("找不到 sendMessage API");
                return false;
            }
            Class<?> payloadType = send.getParameterTypes()[1];
            Object payload = payloadType == String.class ? (Object) json : (Object) bytes;
            sentCount++;
            Object ret = send.invoke(messageApi, nodeId, payload);
            onTask(ret, new Callback() {
                public void done(Object result, Throwable error) {
                    if (error != null) {
                        if (isSignatureError(error)) {
                            failSignature("sendMessage", error);
                        } else {
                            lastError = "sendMessage 失败: " + shortError(error);
                            Repo.lastError = lastError;
                            Repo.log(lastError);
                        }
                        return;
                    }
                    okCount++;
                    ready = true;
                    setState("已连接手环");
                }
            });
            Repo.log("下发 " + bytes.length + "B: " + json);
            return true;
        } catch (Throwable t) {
            setError("sendMessage 异常", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 反射工具

    private static Method findMethod(Class<?> cls, String name, int argc, TypeCheck... checks) {
        if (cls == null) {
            return null;
        }
        Method[] methods = cls.getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != argc) {
                continue;
            }
            boolean ok = true;
            for (int k = 0; k < argc; k++) {
                TypeCheck c = (checks != null && k < checks.length) ? checks[k] : null;
                if (c != null && !c.ok(ps[k])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return m;
            }
        }
        return null;
    }

    /**
     * 给 SDK 返回的 Task 挂回调。只看方法名与参数个数，参数类型从 Task 自身反射得到，
     * 这样不依赖 Task / OnSuccessListener 到底在 com.google.android.gms.tasks 还是
     * com.xiaomi.xms.wearable.task 包下。
     */
    private void onTask(Object task, final Callback cb) {
        if (task == null) {
            cb.done(null, new IllegalStateException("SDK 返回 null Task"));
            return;
        }
        boolean attached = false;
        try {
            Method succ = findMethod(task.getClass(), "addOnSuccessListener", 1, (TypeCheck[]) null);
            if (succ != null) {
                Object listener = makeListenerProxy(succ.getParameterTypes()[0], cb, false);
                if (listener != null) {
                    succ.invoke(task, listener);
                    attached = true;
                }
            }
            Method fail = findMethod(task.getClass(), "addOnFailureListener", 1, (TypeCheck[]) null);
            if (fail != null) {
                Object listener = makeListenerProxy(fail.getParameterTypes()[0], cb, true);
                if (listener != null) {
                    fail.invoke(task, listener);
                    attached = true;
                }
            }
        } catch (Throwable t) {
            Repo.log("挂 Task 回调异常: " + shortError(t));
        }
        if (!attached) {
            // 兜底：有些旧版 SDK 直接返回结果对象（List 等），没有回调
            if (task instanceof Collection || task instanceof Iterable
                    || task.getClass().isArray()) {
                cb.done(task, null);
            } else {
                cb.done(null, new IllegalStateException(
                        "Task 无 addOnSuccessListener/addOnFailureListener: "
                                + task.getClass().getName()));
            }
        }
    }

    private static Object makeListenerProxy(Class<?> iface, final Callback cb, final boolean failure) {
        if (iface == null || !iface.isInterface()) {
            return null;
        }
        try {
            return Proxy.newProxyInstance(Bridge.class.getClassLoader(),
                    new Class<?>[] {iface}, new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String n = method.getName();
                            if ("toString".equals(n)) {
                                return "GaoDeNavTaskListener";
                            }
                            if ("hashCode".equals(n)) {
                                return Integer.valueOf(System.identityHashCode(proxy));
                            }
                            if ("equals".equals(n)) {
                                Object other = (args != null && args.length > 0) ? args[0] : null;
                                return Boolean.valueOf(proxy == other);
                            }
                            Object arg = (args != null && args.length > 0) ? args[0] : null;
                            try {
                                if (failure) {
                                    cb.done(arg, asThrowable(arg));
                                } else {
                                    cb.done(arg, null);
                                }
                            } catch (Throwable t) {
                                Repo.log("回调处理异常: " + shortError(t));
                            }
                            return defaultValue(method.getReturnType());
                        }
                    });
        } catch (Throwable t) {
            Repo.log("创建监听代理失败: " + shortError(t));
            return null;
        }
    }

    /** 手环 -> 手机下行监听（本方案用不到上行，但注册后通道才稳定） */
    private static Object makeMessageListener(Class<?> iface) {
        if (iface == null || !iface.isInterface()) {
            return null;
        }
        try {
            return Proxy.newProxyInstance(Bridge.class.getClassLoader(),
                    new Class<?>[] {iface}, new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String n = method.getName();
                            if ("toString".equals(n)) {
                                return "GaoDeNavMessageListener";
                            }
                            if ("hashCode".equals(n)) {
                                return Integer.valueOf(System.identityHashCode(proxy));
                            }
                            if ("equals".equals(n)) {
                                Object other = (args != null && args.length > 0) ? args[0] : null;
                                return Boolean.valueOf(proxy == other);
                            }
                            try {
                                if (args != null) {
                                    for (int i = 0; i < args.length; i++) {
                                        if (args[i] instanceof byte[]) {
                                            String s = new String((byte[]) args[i], UTF8);
                                            Repo.log("手环上行: " + s);
                                            break;
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                Repo.log("处理上行帧异常: " + shortError(t));
                            }
                            return defaultValue(method.getReturnType());
                        }
                    });
        } catch (Throwable t) {
            Repo.log("创建 message listener 失败: " + shortError(t));
            return null;
        }
    }

    private static Object defaultValue(Class<?> t) {
        if (t == null || t == Void.TYPE) {
            return null;
        }
        if (t == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (t == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (t == Long.TYPE) {
            return Long.valueOf(0);
        }
        if (t == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (t == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (t == Float.TYPE) {
            return Float.valueOf(0);
        }
        if (t == Double.TYPE) {
            return Double.valueOf(0);
        }
        if (t == Character.TYPE) {
            return Character.valueOf('\0');
        }
        return null;
    }

    private static List<Object> toList(Object o) {
        List<Object> out = new ArrayList<Object>();
        if (o == null) {
            return out;
        }
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            for (int i = 0; i < l.size(); i++) {
                out.add(l.get(i));
            }
            return out;
        }
        if (o instanceof Collection) {
            Collection<?> c = (Collection<?>) o;
            for (Object x : c) {
                out.add(x);
            }
            return out;
        }
        if (o.getClass().isArray()) {
            int n = Array.getLength(o);
            for (int i = 0; i < n; i++) {
                out.add(Array.get(o, i));
            }
            return out;
        }
        out.add(o);
        return out;
    }

    private static boolean firstBoolean(Object result, boolean def) {
        if (result == null) {
            return def;
        }
        if (result instanceof Boolean) {
            return ((Boolean) result).booleanValue();
        }
        Object first = null;
        if (result.getClass().isArray()) {
            if (Array.getLength(result) == 0) {
                return def;
            }
            first = Array.get(result, 0);
        } else if (result instanceof List) {
            List<?> l = (List<?>) result;
            if (l.isEmpty()) {
                return def;
            }
            first = l.get(0);
        }
        if (first instanceof Boolean) {
            return ((Boolean) first).booleanValue();
        }
        return def;
    }

    private static String nodeIdOf(Object node) {
        if (node == null) {
            return null;
        }
        try {
            Method m = findMethod(node.getClass(), "getId", 0, (TypeCheck[]) null);
            if (m != null) {
                Object r = m.invoke(node);
                if (r != null) {
                    return String.valueOf(r);
                }
            }
        } catch (Throwable ignore) {
            // 继续尝试字段
        }
        try {
            Field f = node.getClass().getField("id");
            Object r = f.get(node);
            if (r != null) {
                return String.valueOf(r);
            }
        } catch (Throwable ignore) {
            // ignore
        }
        return null;
    }

    private static Throwable asThrowable(Object o) {
        if (o instanceof Throwable) {
            return (Throwable) o;
        }
        return new IllegalStateException(String.valueOf(o));
    }

    /** 是否「签名不一致」类错误 */
    public static boolean isSignatureError(Throwable t) {
        Throwable c = t;
        for (int i = 0; c != null && i < 8; i++) {
            String cn = c.getClass().getName();
            String msg = c.getMessage() == null ? "" : c.getMessage();
            if (cn.contains("SignatureVerifyFailed")) {
                return true;
            }
            if (msg.toLowerCase().contains("fingerprint verify failed")) {
                return true;
            }
            if (msg.contains("SignatureVerifyFailed")
                    || msg.contains("签名校验失败") || msg.contains("证书不一致")) {
                return true;
            }
            c = c.getCause();
        }
        return false;
    }

    // ---------------------------------------------------------------- 状态输出

    private void fail(String message) {
        lastError = message;
        Repo.lastError = message;
        setState(message);
        Repo.log(message);
        finishConnect();
    }

    private void failSignature(String where, Throwable error) {
        String msg = "手环互联签名校验失败（APK 与 rpk 证书不一致）[" + where + "]";
        lastError = msg;
        Repo.lastError = msg + " 原始: " + shortError(error);
        setState("签名不一致，请两端统一证书后重装");
        Repo.log(msg);
        Repo.log("修复：① 手环上卸载旧的「高德导航」快应用 ② 重新安装 dist/cn.dsh.gaodenav.rpk "
                + "③ 小米运动健康里重新授权本 App");
        ready = false;
        signatureError = true;
        finishConnect();
    }

    private void setError(String prefix, Throwable t) {
        lastError = prefix + ": " + shortError(t);
        Repo.lastError = lastError;
        Repo.log(lastError);
        Log.e(TAG, lastError, t);
    }

    private void setState(String s) {
        state = s;
        Repo.bandState = s;
    }

    private static String shortError(Throwable t) {
        if (t == null) {
            return "null";
        }
        String msg = t.getMessage();
        String cn = t.getClass().getSimpleName();
        Throwable cause = t.getCause();
        StringBuilder sb = new StringBuilder();
        sb.append(cn);
        if (msg != null && msg.length() > 0) {
            sb.append(": ").append(msg);
        }
        int depth = 0;
        while (cause != null && cause != t && depth < 3) {
            sb.append(" <- ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            t = cause;
            cause = cause.getCause();
            depth++;
        }
        String s = sb.toString();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    // ---------------------------------------------------------------- 只读访问

    public boolean isReady() {
        return ready;
    }

    /** 当前是否处于「APK 与 rpk 证书不一致」状态（自动重连据此退避 60s） */
    public boolean isSignatureErrorState() {
        return signatureError;
    }

    public boolean isListening() {
        return listenerRegistered;
    }

    public String stateText() {
        return state;
    }

    public String lastErrorText() {
        return lastError;
    }

    public String nodeIdText() {
        return nodeId == null ? "（无）" : nodeId;
    }

    public int sentCount() {
        return sentCount;
    }

    public int okCount() {
        return okCount;
    }
}
