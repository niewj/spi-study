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
