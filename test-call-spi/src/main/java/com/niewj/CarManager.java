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
