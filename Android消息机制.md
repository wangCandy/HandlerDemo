
###一、前言
    提到消息机制大家应该都不陌生，日常开发过程中相信大家都碰到过需要在主线程和子线程之间进行消息通信的操作，例如有些时候我们需要在子线程中进行一些I/O耗时操作，完成之后又需要对UI进行修改，而Android规定UI只能在主线程中进行访问，否则会触发程序异常，此时我们使用Handler就可以在线程间进行切换而不受影响。Android的消息机制主要就是指Handler的运行机制以及Handler所附带的MessageQueue和Looper的工作过程。

![Android消息机制.jpg](https://upload-images.jianshu.io/upload_images/5914751-e15d9087c0b62324.jpg?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

### 二、Android消息机制

#### 2.1 Handler的使用

```java
Handler mHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            //处理消息
        }
    };

//发送消息
mHandler.sendMessage(msg);
mHandler.post(runnable);
```

​	Handler的常见使用就是创建Handler之后重写handleMassage方法进行消息处理，同时Handler还提供了send的一系列方法和post一系列方法进行消息的发送，Handler还支持消息的延迟发送，具体API可以查看Handler源码。

#### 2.2 Handler的工作原理

​	我们从Handler的使用看来，根本没有MessageQueue和Looper的身影，那么他们具体是怎么合作实现线程间的消息切换的呢。首先我们看Handler的构造方法：

```java
public Handler(Callback callback, boolean async) {
        ....
        mLooper = Looper.myLooper();
        if (mLooper == null) {
            throw new RuntimeException(
                "Can't create handler inside thread that has not called Looper.prepare()");
        }
        mQueue = mLooper.mQueue;
        mCallback = callback;
        mAsynchronous = async;
    }

/**
     * Return the Looper object associated with the current thread.  Returns
     * null if the calling thread is not associated with a Looper.
     */
    public static @Nullable Looper myLooper() {
        return sThreadLocal.get();
    }
```

​	在构造方法中，Handler会调用Looper.mLooper方法，从ThreadLocal中获取到当前线程的looper并获取MessageQueue。那么很多人也会问，我们并没有创建Looper，这是因为我们日常创建Handler时都是在主线程创建的，而主线程在创建时就已经为我们创建好了Looper。如果我们在子线程中创建Handler的话，则必须调用Looper的prepare和loop方法。子线程使用Handler如下：

```java
class TestThread extends Thread{
        Handler mHandler;
        @Override
        public void run() {
            super.run();
            Looper.prepare();
            mHandler = new Handler(){
                @Override
                public void handleMessage(Message msg) {
                    super.handleMessage(msg);
                    //处理消息
                }
            };
            Looper.loop();
        }
    }
```

​	Looper的prepare方法对Looper进行了创建并放入ThreadLocal中，而loop方法会不断的从MessageQueue中获取消息并处理，最终通过`msg.target.dispatchMessage(msg)`将消息交给Handler的dispatchMessage方法。dispatchMessage方法中Message中的callback方法，从源码可以看出这其实就是post方法中的runnable。其次处理自定义的callback，最后调用handleMessage方法。

```java
/**
     * Handle system messages here.
     */
    public void dispatchMessage(Message msg) {
        if (msg.callback != null) {
            handleCallback(msg);
        } else {
            if (mCallback != null) {
                if (mCallback.handleMessage(msg)) {
                    return;
                }
            }
            handleMessage(msg);
        }
    }
```

​	接下来我们看一下Handler的消息发送，Handler主要提供了post和send两种消息调度方法，通过post方法将一个Runnable投递到Handler内部的Looper当中处理，send方法则是将一个消息投递到Handler内部的Looper当中去处理。从源码可以看出来post方法其实就是将runnable包装成Message进行发送，最终也是通过调用send方法实现的，所以我们主要看看send方法的工作过程。

```java
public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
        MessageQueue queue = mQueue;
        if (queue == null) {
            RuntimeException e = new RuntimeException(
                    this + " sendMessageAtTime() called with no mQueue");
            Log.w("Looper", e.getMessage(), e);
            return false;
        }
        return enqueueMessage(queue, msg, uptimeMillis);
    }

private boolean enqueueMessage(MessageQueue queue, Message msg, long uptimeMillis) {
        msg.target = this;
        if (mAsynchronous) {
            msg.setAsynchronous(true);
        }
        return queue.enqueueMessage(msg, uptimeMillis);
    }
```

​	可以看到send方法在拿到消息之后，会调用MessageQueue的enqueueMessage方法将消息放入消息队列中。然后Looper发现新消息到来之后就会处理这个消息，最终消息中的Runnable或者Handler中的handleMessage会被调用，工作流程图如下：

![Handler工作过程](/Users/liyanwang/Downloads/Handler工作过程.jpg)

**注意**：我们前面提到过Handler支持延迟消息发送，那么延迟消息是怎么处理的呢。其实当消息后将延迟时间赋值给了Message的when，并将消息入队列。而looper在获取消息时会对这个数值进行判断，如果当前未达到消息处理时间则不处理。

#### 2.3 消息队列的工作原理

​	消息队列主要功能就是对消息进行管理。MessageQueue其实很简单，主要用到插入`(enqueueMessage)`和读取`(next)`两个操作，虽然称之为消息队列，但其实内部是使用单链表实现的。单链表对于插入删除更方便，不赘述。下面为消息队列的插入操作，其实就是一个简单的单链表插入操作：

```java
boolean enqueueMessage(Message msg, long when) {
        ......
            Message p = mMessages;
            boolean needWake;
            if (p == null || when == 0 || when < p.when) {
                // New head, wake up the event queue if blocked.
                msg.next = p;
                mMessages = msg;
                needWake = mBlocked;
            } else {
                // Inserted within the middle of the queue.  Usually we don't have to wake
                // up the event queue unless there is a barrier at the head of the queue
                // and the message is the earliest asynchronous message in the queue.
                needWake = mBlocked && p.target == null && msg.isAsynchronous();
                Message prev;
                for (;;) {
                    prev = p;
                    p = p.next;
                    if (p == null || when < p.when) {
                        break;
                    }
                    if (needWake && p.isAsynchronous()) {
                        needWake = false;
                    }
                }
                msg.next = p; // invariant: p == prev.next
                prev.next = msg;
            }

            // We can assume mPtr != 0 because mQuitting is false.
            if (needWake) {
                nativeWake(mPtr);
            }
        }
        return true;
    }
```

​	下面为消息队列的读取操作，队列读取操作就是读取链表最后一个数据并删除：

```java
Message next() {
        ....
        int pendingIdleHandlerCount = -1; // -1 only during first iteration
        int nextPollTimeoutMillis = 0;
        for (;;) {
            if (nextPollTimeoutMillis != 0) {
                Binder.flushPendingCommands();
            }

            nativePollOnce(ptr, nextPollTimeoutMillis);

            synchronized (this) {
                // Try to retrieve the next message.  Return if found.
                final long now = SystemClock.uptimeMillis();
                Message prevMsg = null;
                Message msg = mMessages;
                if (msg != null && msg.target == null) {
                    // Stalled by a barrier.  Find the next asynchronous message in the queue.
                    do {
                        prevMsg = msg;
                        msg = msg.next;
                    } while (msg != null && !msg.isAsynchronous());
                }
                if (msg != null) {
                    if (now < msg.when) {
                        // Next message is not ready.  Set a timeout to wake up when it is ready.
                        nextPollTimeoutMillis = (int) Math.min(msg.when - now, Integer.MAX_VALUE);
                    } else {
                        // Got a message.
                        mBlocked = false;
                        if (prevMsg != null) {
                            prevMsg.next = msg.next;
                        } else {
                            mMessages = msg.next;
                        }
                        msg.next = null;
                        if (DEBUG) Log.v(TAG, "Returning message: " + msg);
                        msg.markInUse();
                        return msg;
                    }
                } else {
                    // No more messages.
                    nextPollTimeoutMillis = -1;
                }
              ....
    }
```

#### 2.4 Looper的工作原理

​	looper主要的功能在于循环进行消息的读取与处理，首先我们看一下Looper的构造方法，可以看到Looper的构造方法中初始化了MessageQueue并获取了当前线程：

```java
private Looper(boolean quitAllowed) {
        mQueue = new MessageQueue(quitAllowed);
        mThread = Thread.currentThread();
    }
```

Looper中提供了prepare方法进行初始化工作，主要内容就是初始化Looper并放入ThreadLocal中，如下：

```java
private static void prepare(boolean quitAllowed) {
        if (sThreadLocal.get() != null) {
            throw new RuntimeException("Only one Looper may be created per thread");
        }
        sThreadLocal.set(new Looper(quitAllowed));
    }
```

这里需要说的一点就是由于主线程比较特殊，所以Looper专门主线程提供了`prepareMainLooper`和`getMainLooper`方法进行主线程的初始化和获取，使用`getMainLooper`方法我们可以在任何地方获取到主线程的Looper。除此之外Looper还提供了quit和quitSafely方法来退出Looper，quit方法会立即退出Looper，而quitSafely方法则是当前不再队列里添加消息，等队列内消息处理结束后退出Looper。

​	Looper中另一个重要的方法就是loop了，这个方法对消息队列进行循环读取并对消息进行处理，我们看一下loop方法的具体内容：

```java
/**
     * Run the message queue in this thread. Be sure to call
     * {@link #quit()} to end the loop.
     */
    public static void loop() {
        .....
        for (;;) {
            Message msg = queue.next(); // might block
            if (msg == null) {
                // No message indicates that the message queue is quitting.
                return;
            }
          
            // This must be in a local variable, in case a UI event sets the logger
            final Printer logging = me.mLogging;
            if (logging != null) {
                logging.println(">>>>> Dispatching to " + msg.target + " " +
                        msg.callback + ": " + msg.what);
            }
          
            final long slowDispatchThresholdMs = me.mSlowDispatchThresholdMs;

            final long traceTag = me.mTraceTag;
            if (traceTag != 0 && Trace.isTagEnabled(traceTag)) {
                Trace.traceBegin(traceTag, msg.target.getTraceName(msg));
            }
            final long start = (slowDispatchThresholdMs == 0) ? 0 : SystemClock.uptimeMillis();
            final long end;
            try {
                msg.target.dispatchMessage(msg);
                end = (slowDispatchThresholdMs == 0) ? 0 : SystemClock.uptimeMillis();
            } finally {
                if (traceTag != 0) {
                    Trace.traceEnd(traceTag);
                }
            }
            if (slowDispatchThresholdMs > 0) {
                final long time = end - start;
                if (time > slowDispatchThresholdMs) {
                    Slog.w(TAG, "Dispatch took " + time + "ms on "
                            + Thread.currentThread().getName() + ", h=" +
                            msg.target + " cb=" + msg.callback + " msg=" + msg.what);
                }
            }

            if (logging != null) {
                logging.println("<<<<< Finished to " + msg.target + " " + msg.callback);
            }

            // Make sure that during the course of dispatching the
            // identity of the thread wasn't corrupted.
            final long newIdent = Binder.clearCallingIdentity();
            if (ident != newIdent) {
                Log.wtf(TAG, "Thread identity changed from 0x"
                        + Long.toHexString(ident) + " to 0x"
                        + Long.toHexString(newIdent) + " while dispatching to "
                        + msg.target.getClass().getName() + " "
                        + msg.callback + " what=" + msg.what);
            }

            msg.recycleUnchecked();
        }
    }
```

我们可以看到loop方法中一直在循环读取消息队列，跳出循环的方式就是msg.next为空。读到消息之后会会调用`msg.target.dispatchMessage(msg)`，这里的msg.target是发送这条消息的Handler对象，这样Handler发送的消息最终又将在dispatchMessage方法中处理，这样就切换到指定线程中了。

#### 2.5 ThreadLocal的工作原理

​	在前面我们一再提到ThreadLocal，这个到底有什么用呢，我们先看一下源码中对ThreadLocal的用途说明

```
This class provides thread-local variables.  These variables differ from their normal counterparts in that each thread that accesses one (via its {@code get} or {@code set} method) has its own, independently initialized copy of the variable.  {@code ThreadLocal} instances are typically private static fields in classes that wish to associate state with a thread (e.g., a user ID or Transaction ID).
```

简言之，该类提供了线程的局部变量，通过get和set方法可以对当前线程的局部变量进行获取和修改，保证线程之间的数据隔离。

我们先看一下ThreadLocal的set方法，如下：

```java
/**
     * Sets the current thread's copy of this thread-local variable
     * to the specified value.  Most subclasses will have no need to
     * override this method, relying solely on the {@link #initialValue}
     * method to set the values of thread-locals.
     *
     * @param value the value to be stored in the current thread's copy of
     *        this thread-local.
     */
    public void set(T value) {
        Thread t = Thread.currentThread();
        ThreadLocalMap map = getMap(t);
        if (map != null)
            map.set(this, value);
        else
            createMap(t, value);
    }
```

set方法其实很简单，获取当前线程后，根据当前线程获取到存储线程信息的ThreadLocalMap，如果存在则将变量放入map中，不存在则创建一个map后将数据存入。

put方法如下：

```java
/**
     * Returns the value in the current thread's copy of this
     * thread-local variable.  If the variable has no value for the
     * current thread, it is first initialized to the value returned
     * by an invocation of the {@link #initialValue} method.
     *
     * @return the current thread's value of this thread-local
     */
    public T get() {
        Thread t = Thread.currentThread();
        ThreadLocalMap map = getMap(t);
        if (map != null) {
            ThreadLocalMap.Entry e = map.getEntry(this);
            if (e != null) {
                @SuppressWarnings("unchecked")
                T result = (T)e.value;
                return result;
            }
        }
        return setInitialValue();
    }
```

put方法也很简单，获取当前线程后，根据当前线程取出该线程对应的Map，然后根据value取出对应的数据。

### 三、扩展

#### 3.1 使用Handler造成内存泄漏

​	内存泄漏原因：当一个对象已经不再被使用时，本该被回收但却因为有另外一个正在使用的对象持有它的引用从而导致它不能被回收，从而造成内存泄漏。

​	在我们日常使用Handler的时候，经常是新建一个Handler子类或者一个匿名内部类。如果我们使用Handler发送一个延迟消息，而在延迟期间将activity关闭，此时Message 会持有 Handler，而又因为 Java 的特性，内部类会持有外部类，使得 Activity 会被 Handler 持有，这样最终就导致 Activity 泄露。解决方法就是创建一个匿名静态内部类，然后采用弱引用的方式进行初始化，并在onDestory的时候及时移除消息。

```java
private static class MyHandler extends Handler {
        private final WeakReference<TestActivity> mActivity;

        private MyHandler(TestActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
        }
    }
```

#### 3.2 使用Looper检测界面卡顿

​	在Looper的loop方法中我们可以看到，当事件开始处理和结束都会有log打印，我们可以利用这个log打印来判断界面卡顿处具体代码如下：

```kotlin
class MainActivity : AppCompatActivity() {

    private val checkHandler = CheckHandler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        check()
        btn_check.setOnClickListener {
            Thread.sleep(2000)
        }
    }

    private fun check(){
        Looper.getMainLooper().setMessageLogging {
            if (it.startsWith(">>>>> Dispatching to")){
                checkHandler.onStart()
            }else if (it.startsWith("<<<<< Finished to")){
                checkHandler.onEnd()
            }
        }
    }
    class CheckHandler {
        private val mHandlerThread = HandlerThread("卡顿检测")
        private var mHandler : Handler
        
        private val runnable = Runnable {
            log()
        }

        fun onStart(){
            mHandler.postDelayed(runnable , 1000)
        }

        fun onEnd(){
            mHandler.removeCallbacksAndMessages(null)
        }

        private fun log() {
            val sb = StringBuilder()
            val stackTrace = Looper.getMainLooper().thread.stackTrace
            stackTrace.forEach {
                sb.append("$it\n")
            }
            Log.w("TAG", sb.toString())
        }

        init {
            mHandlerThread.start()
            mHandler = Handler(mHandlerThread.looper)
        }
    }
}
```

通过这个我们就可以检测出界面卡顿超过一秒的地方。