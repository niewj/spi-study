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