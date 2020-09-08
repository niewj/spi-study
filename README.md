# 1. JDBC获取Connection

## 1.1 maven依赖引入mysql-connector和h2数据库

```xml
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
    <version>8.0.21</version>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>1.4.200</version>
</dependency>
```

## 1.2 java获取连接代码-获取mysql的连接

```java
package com.niewj;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class App {
    public static void main(String[] args) {
        String url = "jdbc:mysql://localhost:3306/test?serverTimezone=UTC";
        try {
            Connection conn = DriverManager.getConnection(url, "root", "1234");
            System.out.println(conn);
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }
}
```

## 1.3 输出

```shell
com.mysql.cj.jdbc.ConnectionImpl@32b260fa
```

这么一段程序, 并没有写 `Class.forName("com.mysql.cj.jdbc.Driver")` 却能获取到mysql的数据库连接，这是什么原因呢？我们先看一下现象(呈现出来的能看到的东西)：

## 1.4 观察:

<img src="../notes/imgs/spi-001.png" alt="image-20200908134354910" style="zoom: 50%;" />

### 1.4.1 MySQL的jdbc驱动jar包：

`META-INFO/services/java.sql.Driver` 文件,内容：

> com.mysql.cj.jdbc.Driver

### 1.4.2 H2的jdbc驱动jar包：
`META-INFO/services/java.sql.Driver` 文件,内容：

> org.h2.Driver

可以看到

1. 包名是`META-INF`;
2. 文件名是**接口**类路径;
3. 文件内容是**实现**类全路径;

# 2.  缘由探究-深入DriverManager源码

DriverManager中的实现：

```java
public class DriverManager {
    // List of registered JDBC drivers
    private final static CopyOnWriteArrayList<DriverInfo> registeredDrivers = new CopyOnWriteArrayList<>();
    private static volatile int loginTimeout = 0;
    private static volatile java.io.PrintWriter logWriter = null;
    private static volatile java.io.PrintStream logStream = null;
    // Used in println() to synchronize logWriter
    private final static  Object logSync = new Object();
```

第一处：`registeredDrivers` 是一个List, 而且是线程安全的List: CopyOnWriteArrayList;

第二处: static块：我们知道，static是类初始化以前就加载的， 这里还调用了一个static方法：`loadInitialDrivers()`

```java
/**
 * Load the initial JDBC drivers by checking the System property
 * jdbc.properties and then use the {@code ServiceLoader} mechanism
 */
static {
    loadInitialDrivers();
    println("JDBC DriverManager initialized");
}
```

看注释中已经说了： 加载初始化的JDBC驱动(注意是复数形式)， 提到了 `use the ServiceLoader`机制

name什么是 `ServiceLoader`呢？ 接下来看：

第三处：`loadInitialDrivers()`, 主要就在第20行， 调用了 `ServiceLoader`!

```java
private static void loadInitialDrivers() {
	String drivers;
	try {
		drivers = AccessController.doPrivileged(new PrivilegedAction<String>() {
			public String run() {
				return System.getProperty("jdbc.drivers");
			}
		});
	} catch (Exception ex) {
		drivers = null;
	}
	// If the driver is packaged as a Service Provider, load it.
	// Get all the drivers through the classloader
	// exposed as a java.sql.Driver.class service.
	// ServiceLoader.load() replaces the sun.misc.Providers()

	AccessController.doPrivileged(new PrivilegedAction<Void>() {
		public Void run() {

			ServiceLoader<Driver> loadedDrivers = ServiceLoader.load(Driver.class);
			Iterator<Driver> driversIterator = loadedDrivers.iterator();

			/* Load these drivers, so that they can be instantiated.
			 * It may be the case that the driver class may not be there
			 * i.e. there may be a packaged driver with the service class
			 * as implementation of java.sql.Driver but the actual class
			 * may be missing. In that case a java.util.ServiceConfigurationError
			 * will be thrown at runtime by the VM trying to locate
			 * and load the service.
			 *
			 * Adding a try catch block to catch those runtime errors
			 * if driver not available in classpath but it's
			 * packaged as service and that service is there in classpath.
			 */
			try{
				while(driversIterator.hasNext()) {
					driversIterator.next();
				}
			} catch(Throwable t) {
			// Do nothing
			}
			return null;
		}
	});

	println("DriverManager.initialize: jdbc.drivers = " + drivers);

	if (drivers == null || drivers.equals("")) {
		return;
	}
	String[] driversList = drivers.split(":");
	println("number of Drivers:" + driversList.length);
	for (String aDriver : driversList) {
		try {
			println("DriverManager.Initialize: loading " + aDriver);
			Class.forName(aDriver, true,
					ClassLoader.getSystemClassLoader());
		} catch (Exception ex) {
			println("DriverManager.Initialize: load failed: " + ex);
		}
	}
}
```

12-15行注释已经说明白:

```shell
// If the driver is packaged as a Service Provider, load it.
// Get all the drivers through the classloader
// exposed as a java.sql.Driver.class service.
// ServiceLoader.load() replaces the sun.misc.Providers()

1. 如果driver已经打包为一个 Service Provider, 那么就load it;
2. 通过类加载器获取所有暴露为 java.sql.Driver.class的服务的那些drivers
```

可以看到， 这里说了， 是 ServiceLoader把所有能在ClassLoader里加载到的接口的实现，都打包到上面声明的List里了， 所以我们在调用的时候， 不用指定具体数据源的类型， 只需要通过遍历我们的url， 它就会帮我们匹配是哪个driver了；

# 3. JDK中的ServiceLoader

## 3.1 参考JDK文档内容

那么下来我们研究下ServiceLoader是谁， 从API注释说起： 

下面是一段jdk7的中文文档api说明，因为ServiceLoader类是jdk1.6引入的， 所以我们看他就够了：

>通过在资源目录 `META-INF/services` 中放置*提供者配置文件* 来标识服务提供者。文件名称是服务类型的完全限定[二进制名称](https://tool.oschina.net/uploads/apidocs/jdk-zh/java/lang/ClassLoader.html#name)。该文件包含一个具体提供者类的完全限定二进制名称列表，每行一个。忽略各名称周围的空格、制表符和空行。注释字符为 `'#'` (`'\u0023'`, NUMBER SIGN)；忽略每行第一个注释字符后面的所有字符。文件必须使用 UTF-8 编码。
>
>如果在多个配置文件中指定了一个特定的具体提供者类，或在同一配置文件中多次被指定，则忽略重复的指定。指定特定提供者的配置文件不必像提供者本身一样位于同一个 jar 文件或其他的分布式单元中。提供者必须可以从最初为了定位配置文件而查询的类加载器访问；注意，这不一定是实际加载文件的类加载器。
>
>以延迟方式查找和实例化提供者，也就是说根据需要进行。服务加载器维护到目前为止已经加载的提供者缓存。每次调用 [`iterator`](https://tool.oschina.net/uploads/apidocs/jdk-zh/java/util/ServiceLoader.html#iterator()) 方法返回一个迭代器，它首先按照实例化顺序生成缓存的所有元素，然后以延迟方式查找和实例化所有剩余的提供者，依次将每个提供者添加到缓存。可以通过 [`reload`](https://tool.oschina.net/uploads/apidocs/jdk-zh/java/util/ServiceLoader.html#reload()) 方法清除缓存。
>
>服务加载器始终在调用者的安全上下文中执行。受信任的系统代码通常应当从特权安全上下文内部调用此类中的方法，以及它们返回的迭代器的方法。
>
>此类的实例用于多个并发线程是不安全的。

这里头提到了几个点：

1. 在资源目录 `META-INF/services` 中放置*提供者配置文件*
2. 文件名称是服务类型的完全限定名称
3. 文件包含一个具体提供者类的完全限定二进制名称列表，每行一个

我们跟着模仿DriverManager的机制来写一个简单的实例来研究下：

# 4. 模仿DriverManager案例

## 4.1 案例：Car+SuvCar+RacingCar+调用

### 4.1.2. 案例maven结构概览

1. 一个`spi-common`模块：Car接口声明： 模拟 **java.sql.Driver接口**
2. 一个`spi-suv-car`模块：Car的实现类SuvCar实现类: 模拟 **MySQL数据库连接实现类**
3. 一个`spi-racing-car`模块：Car的实现类RacingCar的实现类:  模拟 **H2内存数据库连接实现类**
4. 调用模块的项目: `test-call-spi`

## 4.2. 案例代码-(1): spi-common模块

### 4.2.1. maven配置(无特殊依赖)略

### 4.2.2. Car.java接口

```java
package com.niewj;
/**
 * Created by niewj on 2020/9/8 14:16
 */
public interface Car {
    void drive();
}
```

## 4.3. 案例代码-(2): spi-suv-car

### 4.3.1. maven配置(引入了Car.java接口)

```xml
<dependency>
    <groupId>com.niewj</groupId>
    <artifactId>spi-common</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 4.3.2 SuvCar.java实现类

```java
package com.niewj.suv.service.impl;

import com.niewj.Car;

/**
 * Created by niewj on 2020/9/8 14:21
 */
public class SuvCar implements Car {
    @Override
    public void drive() {
        System.out.println("========================");
        System.out.println("=I driving a SUVCar, dud-翻山越岭，yeyeye=====");
        System.out.println("========================");
    }
}
```

### 4.3.3 Resource目录下文件：

resources-》META-INF-》services-》com.niewj.Car 文件内容：

```properties
com.niewj.suv.service.impl.SuvCar
```

如图：

<img src="../notes/imgs/spi-suv-car-dir.png" alt="image-20200908152555847" style="zoom:50%;" />

## 4.4. 案例代码-(3): spi-racing-car

整体结构跟 `api-suv-car` 相同；

### 4.4.1. maven配置(引入了Car.java接口)

```xml
<dependency>
    <groupId>com.niewj</groupId>
    <artifactId>spi-common</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 4.4.2 RacingCar.java实现类

```java
package com.niewj.racing.service.impl;

import com.niewj.Car;

/**
 * Created by niewj on 2020/9/8 14:44
 */
public class RacingCar implements Car {
    @Override
    public void drive() {
        System.out.println("-----------------------");
        System.out.println("->---Racing--->--Car--->So~~ 不见了~~");
        System.out.println("-----------------------");
    }
}
```

### 4.4.3 Resource目录下文件：

resources-》META-INF-》services-》com.niewj.Car 文件内容：

```properties
com.niewj.racing.service.impl.RacingCar
```

如图：

<img src="../notes/imgs/spi-racing-car-dir.png" alt="image-20200908153944307" style="zoom:50%;" />

## 4.5. 代码-(4): test-call-spi调用部分

这里我就是引入了两个实现的依赖， 然后模拟DriverManager实现了个简单的CarManager获取Car

### 4.5.1 maven配置

```xml
<dependency>
    <groupId>com.niewj</groupId>
    <artifactId>spi-common</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.niewj</groupId>
    <artifactId>spi-suv-car</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.niewj</groupId>
    <artifactId>spi-racing-car</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 4.5.2 模拟DriverManager的CarManager.java

```java
package com.niewj;

import com.niewj.suv.service.impl.SuvCar;

import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by niewj on 2020/9/8 14:53
 */
public class CarManager {

    private static CopyOnWriteArrayList<Car> carList = new CopyOnWriteArrayList();

    static{
        loadInitialCars();
        System.out.println("初始化 Car 的实现类完成！");
    }

    private static void loadInitialCars() {
        ServiceLoader<Car> loadedCars = ServiceLoader.load(Car.class);
        Iterator<Car> iterator = loadedCars.iterator();
        while(iterator.hasNext()){
            carList.add(iterator.next());
        }
    }

    public static Car getCar(Class className){
        if(carList == null || carList.size() <= 0){
            System.out.println("没有初始化好的Car!!!");
            return null;
        }

        for (Car car : carList) {
            System.out.println(car.getClass().getName().equals(className.getName()));
            if(car.getClass().getName().equals(className.getName())){
                System.out.println("找到匹配的Car: " + className.getName());
                return car;
            }
        }
        return null;
    }
}
```

### 4.5.3 调用获取Car的main方法：

```java
package com.niewj;

import com.niewj.racing.service.impl.RacingCar;
import com.niewj.suv.service.impl.SuvCar;

public class App {
    public static void main(String[] args) {
        Car car = CarManager.getCar(SuvCar.class);
        car.drive();
        System.out.println();
        Car car2 = CarManager.getCar(RacingCar.class);
        car2.drive();
    }
}
```

可以看到， 并没有主动去new或者初始化Car的两个实现类， 但是都能加载到， 这就是SPI啦！

# 5. SPI-千呼万唤始出来

## 5.1 什么是SPI

有了上面的例子，SPI就很easy了。

**SPI** 全称 **Service Provider Interface**，是Java提供的一套扩展的API服务实现机制。

## 5.2 SPI和API概念对比

SPI和API的对比说明：

**API**: 指定接口并提供实现，开发人员直接**使用**API即可；

**SPI**: 多用于制定接口规范，提供服务调用自加载，方便开发人员来实现扩展，易于框架**扩展**人员使用；

## 5.3 SPI的使用方法

### 5.3.1 扩展接口(实现已有的接口)

1. 编写接口的实现类;
2. 指定的目录下配置;
3. 配置目录必须为 META-INF/services;
4. 配置文件名必须为接口的全路径名;
5. 配置文件内容必须为: 接口的实现类的全限定类名;

### 5.3.2 接口的解析(ServiceLoader)

核心的使用加载逻辑就这么两步：

```java
ServiceLoader<Car> loadedCars = ServiceLoader.load(Car.class);
Iterator<Car> iterator = loadedCars.iterator();
// 遍历 iterator 放入你的容器， 后续就可以使用了
```

## 5.4 ServiceLoader的实现(局部源码)

### 5.4.1 目录和容器

```java
public final class ServiceLoader<S> implements Iterable<S> {
 private static final String PREFIX = "META-INF/services/";
 private final Class<S> service;
 private final ClassLoader loader;
 private final AccessControlContext acc;
 // Cached providers, in instantiation order
 private LinkedHashMap<String,S> providers = new LinkedHashMap<>();
 // The current lazy-lookup iterator
 private LazyIterator lookupIterator;
```

1. PREFIX 可以看到， 规定了指定的目录， 需要是 "META-INF/services/"
2. providers是LinkedHashMap， 可以保证初始化的顺序就是放入map的顺序

### 5.4.2 load过程

我们看到加载就用了一句代码： `ServiceLoader.load(Car.class)` 我们跟进去看看：

```java
public static <S> ServiceLoader<S> load(Class<S> service) {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    return ServiceLoader.load(service, cl);
}
```

这里如果不自定义类加载器， 就会取当前线程的上下文类加载器， 用于加载Provider服务；

我们看上面的代码， 得到类加载器后， 又调用了重载的方法。

我们再跟进：发现调用到构造器了：

```java
public static <S> ServiceLoader<S> load(Class<S> service, ClassLoader loader) {
    return new ServiceLoader<>(service, loader);
}
```

再跟构造器：`reload();` 就是它了吧， 它是这构造器里最核心的方法了：

```java
private ServiceLoader(Class<S> svc, ClassLoader cl) {
    service = Objects.requireNonNull(svc, "Service interface cannot be null");
    loader = (cl == null) ? ClassLoader.getSystemClassLoader() : cl;
    acc = (System.getSecurityManager() != null) ? AccessController.getContext() : null;
    reload();
}
```

再去找 `reload();`方法：

```java
public void reload() {
    providers.clear();
    lookupIterator = new LazyIterator(service, loader);
}
```

上面是 reload() 方法， 就两句： 第一句providers声明的LinkedHashMap， clear()当然就是清空容器中已经加载了的实现类；如果是第一次的话， 调用和不调用没区别； 如果是已经加载过了， 那就是清空重来了；

貌似还没有什么有用的加载逻辑出现。。。。等等， 出来个 `LazyIterator` 一个内部类，难道它的构造器负责加载？它的声明也在类中：

```java
    // The current lazy-lookup iterator
    private LazyIterator lookupIterator;
```

其实上面已经出现过了， 声明的ServiceLoader的成员最后一个就是它， 重新列出来， 是因为：

> 初中的时候语文老师说过：
>
> 双重否定强调肯定；
>
> 双重肯定强调重要!

对了， 加载真真就在这个迭代器中， 是在构造器中吗？ 跟进去！！

```java
private LazyIterator(Class<S> service, ClassLoader loader) {
    this.service = service;
    this.loader = loader;
}
```

我去~~失望了， 构造器中就是赋值一下， 而已~而已~而已~而已~

等等， 上面那句注释是啥？？`The current lazy-lookup iterator` 单词认识不多， `lazy` 还是认得的；

lazy? 莫非是内部类会延迟加载？ 难道它有静态方法帮初始化？没道理啊？ 

那是怎么 lazy的？咋整呢！？

<img src="../notes/imgs/what.png" alt="image-20200908173649694" style="zoom:50%;" />

回过头来， 看看我们是怎么用的。。。下面代码：我们是load完之后， 拿到他的迭代器， 然后放入我们自己的集合中。。。莫非。。。。莫非。。。他是在遍历的时候才初始化？lazy懒加载懒到这么懒？？

```java
private static void loadInitialCars() {
    ServiceLoader<Car> loadedCars = ServiceLoader.load(Car.class);
    Iterator<Car> iterator = loadedCars.iterator();
    while(iterator.hasNext()){
        carList.add(iterator.next());
    }
}
```

果然是的呢， 有码为证：LazyIterator.java内部类：hasNext的时候，调用了 `hasNextService()`

```java
public boolean hasNext() {
    if (acc == null) {
        return hasNextService();
    } else {
        PrivilegedAction<Boolean> action = new PrivilegedAction<Boolean>() {
            public Boolean run() { return hasNextService(); }
        };
        return AccessController.doPrivileged(action, acc);
    }
}
```

盲猜： `hasNextService()` 或 `next()`肯定是初始化逻辑了， 如果不是， 我把源码吃了！！

```java
private boolean hasNextService() {
    if (nextName != null) {
        return true;
    }
    if (configs == null) {
        try {
            String fullName = PREFIX + service.getName();
            if (loader == null)
                configs = ClassLoader.getSystemResources(fullName);
            else
                configs = loader.getResources(fullName);
        } catch (IOException x) {
            fail(service, "Error locating configuration files", x);
        }
    }
    while ((pending == null) || !pending.hasNext()) {
        if (!configs.hasMoreElements()) {
            return false;
        }
        pending = parse(service, configs.nextElement());
    }
    nextName = pending.next();
    return true;
}
```

这个是`hasNextService()`:  这里调用了下 类加载器， 加载了 指定路径下的文件到 configs中(configs是什么呢？) ` Enumeration<URL> configs = null;` 原来是个枚举容器；下面还调了个 parse() 方法， 它又干啥了？

```java
private Iterator<String> parse(Class<?> service, URL u) throws ServiceConfigurationError {
    InputStream in = null;
    BufferedReader r = null;
    ArrayList<String> names = new ArrayList<>();
    try {
        in = u.openStream();
        r = new BufferedReader(new InputStreamReader(in, "utf-8"));
        int lc = 1;
        while ((lc = parseLine(service, u, r, lc, names)) >= 0);
    } catch (IOException x) {
        fail(service, "Error reading configuration file", x);
    } finally {
        try {
            if (r != null) r.close();
            if (in != null) in.close();
        } catch (IOException y) {
            fail(service, "Error closing configuration file", y);
        }
    }
    return names.iterator();
}
```

原来parse() 是把 configs里的内容转换为一个iterator迭代器， 里头放的是一个一个的实现类(一行数据是一个name，对应一个实现类的全路径)names=fullName中配置的所有的接口实现类！





哇塞！~ 原来 hasNext迭代时， 是 `读取到实现类的全名放入迭代器啊` 那盲猜也知道， 肯定 `next()`的时候， 就是对这个实现类做初始化(实例化)了啦！！---> 来， 确认下：

```java
public S next() {
    if (acc == null) {
        return nextService();
    } else {
        PrivilegedAction<S> action = new PrivilegedAction<S>() {
            public S run() { return nextService(); }
        };
        return AccessController.doPrivileged(action, acc);
    }
}
```

我们看到 next() 又调用了nextService()：

```java
private S nextService() {
    if (!hasNextService())
        throw new NoSuchElementException();
    String cn = nextName;
    nextName = null;
    Class<?> c = null;
    try {
        c = Class.forName(cn, false, loader);
    } catch (ClassNotFoundException x) {
        fail(service,
             "Provider " + cn + " not found");
    }
    if (!service.isAssignableFrom(c)) {
        fail(service,
             "Provider " + cn  + " not a subtype");
    }
    try {
        S p = service.cast(c.newInstance());
        providers.put(cn, p);
        return p;
    } catch (Throwable x) {
        fail(service,
             "Provider " + cn + " could not be instantiated",
             x);
    }
    throw new Error();          // This cannot happen
}
```

我们看到：

 第8行， 终于调用了 `Class.forName` 并用类加载器加载了它~

第18-19行， 终于干了点实事儿：new了示例， 并做了类型转换；并乖乖的放入到 LinkedHashMap(providers)中了，哎呦喂~~费死个老劲了~ 终于完事了~

# 6. SPI-源码分析小结

我们来缕缕逻辑：

1. `ServiceLoader`在`load`服务实现类时, 并不是马上都初始化了, 而是采用了延迟，怎么延迟加载呢？
2. `ServiceLoader`使用了一个内部类成员 `LazyIterator`, 这两个类都实现了 Iterator 接口类，但是 `ServiceLoader`的实现里也并没有做什么逻辑， 而是直接代理给 `LazyIterator`来帮自己完成， 它只是坐享其成，甩手掌柜而已；
3. `LazyIterator`由于也是Iterator， 所以就有Iterator的特点，就是， 可以在你业务实际调用的时候， 我才在 hasNext()和next()中给你做实际的工作， 雅称就是：`lazy` 延迟加载；
4. `LazyIterator`的`hasNext()`的工作： 读取`META-INF/services/+service.getName()` 这个文件，把里面的每一行放到一个取名叫names的ArrayList中, 然后返回names的Iterator；
5. `LazyIterator`的`next()`的工作：把names的当前迭代的一行(配置的完整实现类类限定名)，先 newInstance, 再转为其实际类型的对象，然后放入LinkedHashMap中；
6. 大功告成；

# 7. SPI相关应用

实际上， SPI机制用来做扩展功能的应用很多，下面3个实例，可供参考， 有兴趣可以研究另外的两个！

## 7.1 JDBC

JDBC就是我们引入的入口， 这里就不再赘述了， 它就是用jdk提供的 `ServiceLoader`来加载的；

## 7.2 Spring Boot

Spring Boot 读取配置文件加载类的机制， 也是SPI！

Spring Boot使用 `SpringFactoriesLoader`来加载 `META-INF/spring.factories` 文件中的配置类来选择加载；它的约定是：

1. 目录必须是jar包下： META-INF/spring.factories, 这个文件必须是 properties文件；
2. 文件中的key和value也有约定：key必须是接口或者抽象类或注解；value是逗号分隔的实现类或注解；
3. key和value的类名， 都必须是 全限定名： full qualified name；

配合 @EnableAutoConfiguration

## 7.3 Dubbo

Dubbo也是使用了SPI机制， 但是跟Spring Boot一样， 也是用自己的实现， 它的加载类是：`ExtensionLoader `

```java
ExtensionLoader<Robot> extensionLoader = ExtensionLoader.getExtensionLoader(Robot.class); // Robot是接口
Robot optimusPrime = extensionLoader.getExtension("optimusPrime");

optimusPrime.sayHello();
Robot bumblebee = extensionLoader.getExtension("bumblebee");
bumblebee.sayHello();
```

1. 加载类是 ExtensionLoader；
2. 需要在 接口上标注 @SPI 注解
3. SPI 所需的配置文件需放置在 META-INF/dubbo 路径下，配置内容如下：

```properties
optimusPrime = org.apache.spi.OptimusPrime
bumblebee = org.apache.spi.Bumblebee
```

key = getExtension时的name; (optimusPrime、bumblebee)

value = 实现类的全限定名；
