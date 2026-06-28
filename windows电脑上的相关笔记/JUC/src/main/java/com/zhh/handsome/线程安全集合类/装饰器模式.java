package com.zhh.handsome.线程安全集合类;

public class 装饰器模式 {



/*
    超详细清晰易懂的讲解一下装饰器模式
    装饰器模式（Decorator Pattern）是一种结构型设计模式，核心思想是：动态地给对象添加额外功能，同时不改变其原有的结构和接口。它就像给手机套壳 —— 手机本身功能不变，但加了保护壳（防摔）、钢化膜（防刮）、磁吸片（方便充电）等 “装饰”，这些装饰可以灵活组合，且随时能换。
    一、为什么需要装饰器模式？
    先看一个问题：假设你要设计一个咖啡店系统，基础咖啡有浓缩咖啡（Espresso）、美式咖啡（Americano），还可以加各种配料（牛奶、糖、奶泡、巧克力等），每种配料都要加价。
    如果用继承实现：
    浓缩咖啡 + 牛奶 → EspressoWithMilk 类
    浓缩咖啡 + 糖 → EspressoWithSugar 类
    浓缩咖啡 + 牛奶 + 糖 → EspressoWithMilkAndSugar 类
...（以此类推，配料越多，类的数量会爆炸）
    这种方式的问题：
    类数量激增（n 种基础咖啡 + m 种配料，可能产生 n×2^m 个类）；
    功能扩展不灵活（新增配料需要修改所有相关类）；
    违反 “开闭原则”（对扩展开放，对修改关闭）。
    而装饰器模式的解决思路是：把 “基础功能” 和 “装饰功能” 分开，让装饰功能可以动态附加到基础功能上，且能自由组合。
    二、装饰器模式的核心角色
    装饰器模式有 4 个核心角色，我们用咖啡店的例子对应理解：
    角色	含义	咖啡店示例
    抽象组件（Component）	定义了被装饰对象和装饰器的共同接口（或抽象类），规定了核心功能。	Coffee 接口（有 cost() 计算价格、description() 描述功能）
    具体组件（ConcreteComponent）	实现抽象组件的基础对象，是被装饰的 “本体”。	Espresso（浓缩咖啡）、Americano（美式咖啡）
    抽象装饰器（Decorator）	实现抽象组件接口，内部持有一个 “抽象组件” 的引用（被装饰的对象），作为所有具体装饰器的父类。	CondimentDecorator（配料装饰器，持有 Coffee 对象）
    具体装饰器（ConcreteDecorator）	继承抽象装饰器，给被装饰对象添加具体的额外功能。	Milk（牛奶）、Sugar（糖）、Chocolate（巧克力）
    三、代码示例：用装饰器模式实现咖啡店系统
    我们用 Java 代码一步步实现，直观感受装饰器模式的工作方式。
            1. 定义抽象组件（Component）：Coffee 接口
    java
            运行
    // 抽象组件：定义咖啡的核心功能
    public interface Coffee {
        double cost();       // 计算价格
        String description(); // 描述咖啡
    }
2. 定义具体组件（ConcreteComponent）：基础咖啡
            java
    运行
    // 具体组件1：浓缩咖啡（被装饰的本体）
    public class Espresso implements Coffee {
        @Override
        public double cost() {
            return 20.0; // 浓缩咖啡基础价20元
        }

        @Override
        public String description() {
            return "浓缩咖啡";
        }
    }

    // 具体组件2：美式咖啡
    public class Americano implements Coffee {
        @Override
        public double cost() {
            return 15.0; // 美式咖啡基础价15元
        }

        @Override
        public String description() {
            return "美式咖啡";
        }
    }
3. 定义抽象装饰器（Decorator）：配料装饰器
    抽象装饰器必须实现 Coffee 接口（和被装饰对象保持一致的接口），并持有一个 Coffee 引用（被装饰的对象）。
    java
            运行
    // 抽象装饰器：所有配料的父类
    public abstract class CondimentDecorator implements Coffee {
        protected Coffee coffee; // 持有被装饰的咖啡对象

        // 构造方法：传入被装饰的咖啡
        public CondimentDecorator(Coffee coffee) {
            this.coffee = coffee;
        }
    }
4. 定义具体装饰器（ConcreteDecorator）：具体配料
    每个配料都是一个具体装饰器，实现 cost() 和 description() 时，在被装饰对象的基础上 “附加” 自己的功能。
    java
            运行
    // 具体装饰器1：牛奶
    public class Milk extends CondimentDecorator {
        public Milk(Coffee coffee) {
            super(coffee);
        }

        @Override
        public double cost() {
            return coffee.cost() + 3.0; // 基础咖啡价 + 牛奶3元
        }

        @Override
        public String description() {
            return coffee.description() + " + 牛奶"; // 基础描述 + 牛奶
        }
    }

    // 具体装饰器2：糖
    public class Sugar extends CondimentDecorator {
        public Sugar(Coffee coffee) {
            super(coffee);
        }

        @Override
        public double cost() {
            return coffee.cost() + 1.0; // 基础咖啡价 + 糖1元
        }

        @Override
        public String description() {
            return coffee.description() + " + 糖";
        }
    }

    // 具体装饰器3：巧克力
    public class Chocolate extends CondimentDecorator {
        public Chocolate(Coffee coffee) {
            super(coffee);
        }

        @Override
        public double cost() {
            return coffee.cost() + 5.0; // 基础咖啡价 + 巧克力5元
        }

        @Override
        public String description() {
            return coffee.description() + " + 巧克力";
        }
    }
5. 使用装饰器：动态组合功能
    现在，我们可以自由搭配咖啡和配料，无需新增类：
    java
            运行
    public class CoffeeShop {
        public static void main(String[] args) {
            // 1. 点一杯纯浓缩咖啡
            Coffee espresso = new Espresso();
            System.out.println(espresso.description() + "，价格：" + espresso.cost());
            // 输出：浓缩咖啡，价格：20.0

            // 2. 点一杯浓缩咖啡 + 牛奶
            Coffee espressoWithMilk = new Milk(new Espresso());
            System.out.println(espressoWithMilk.description() + "，价格：" + espressoWithMilk.cost());
            // 输出：浓缩咖啡 + 牛奶，价格：23.0

            // 3. 点一杯美式咖啡 + 糖 + 巧克力
            Coffee americanoWithSugarAndChocolate = new Chocolate(new Sugar(new Americano()));
            System.out.println(americanoWithSugarAndChocolate.description() + "，价格：" + americanoWithSugarAndChocolate.cost());
            // 输出：美式咖啡 + 糖 + 巧克力，价格：21.0（15+1+5）
        }
    }
    关键点：装饰器是 “嵌套” 使用的 —— 每个装饰器都包裹着前一个对象（基础咖啡或已装饰的对象），最终调用 cost() 或 description() 时，会层层传递并叠加功能。
    四、装饰器模式的核心特点
    不改变原对象：被装饰的对象（如 Espresso）代码完全不变，符合 “开闭原则”；
    动态扩展：可以在运行时给对象添加 / 移除装饰（比如先加牛奶，再换成巧克力）；
    功能组合：多个装饰器可以自由组合（如牛奶 + 糖、糖 + 巧克力等）；
    接口一致：装饰器和被装饰对象实现同一个接口（如 Coffee），使用者无需区分两者，直接调用接口方法即可。
    五、优缺点
    优点：
    比继承更灵活：避免类爆炸，功能可以动态组合；
    符合开闭原则：新增功能只需加新装饰器，无需修改原有代码；
    可以对同一个对象多次装饰，实现功能叠加。
    缺点：
    可能产生大量细粒度装饰器（如每种配料一个类）；
    调试复杂：调用链是嵌套的，排查问题时需要层层剥开装饰器。
    六、适用场景
    需要动态、灵活地给对象添加功能（如 Java IO 中的流装饰）；
    避免用继承扩展功能（防止类数量爆炸）；
    功能可以组合使用（如咖啡 + 多种配料、手机 + 多种配件）。
    七、经典应用：Java IO 中的装饰器模式
    Java 的 IO 流是装饰器模式的典型应用：
    抽象组件（Component）：InputStream、OutputStream、Reader、Writer；
    具体组件（ConcreteComponent）：FileInputStream（文件输入流，基础功能）、ByteArrayInputStream 等；
    抽象装饰器（Decorator）：FilterInputStream（持有 InputStream 引用）；
    具体装饰器（ConcreteDecorator）：BufferedInputStream（缓冲功能）、DataInputStream（数据类型转换功能）等。
    例如：
    java
            运行
    // 用缓冲功能装饰文件输入流
    InputStream in = new BufferedInputStream(new FileInputStream("test.txt"));
    这里 FileInputStream 是被装饰的本体，BufferedInputStream 是装饰器，给它添加了缓冲功能。
    总结
    装饰器模式就像 “给对象穿衣服”：基础对象是 “裸体”，装饰器是 “外套”，可以穿一件、多件，还能随时换，且不影响 “裸体” 本身的结构。它的核心价值是灵活扩展、动态组合，完美解决了继承带来的类爆炸问题。*/












}
