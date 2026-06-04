package com.agora.service;


import com.agora.model.User;
import com.agora.model.UserAddress;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface UserAddressService {

    /**
     * 創建地址
     */
    UserAddress createAddress(UserAddress address, User user);

    /**
     * 更新地址
     */
    UserAddress updateAddress(UserAddress address, User user);

    /**
     * 刪除地址
     */
    void deleteAddress(Long addressId, User user);

    /**
     * 設置預設地址
     */
    UserAddress setDefaultAddress(Long addressId, User user);

    /**
     * 獲取用戶所有地址
     */
    List<UserAddress> getUserAddresses(User user);
    
    /**
     * 獲取賣家退貨地址列表
     */
    List<UserAddress> getReturnAddList(Long sellerId);

    /**
     * 分頁獲取用戶地址
     */
    Page<UserAddress> getUserAddresses(User user, Pageable pageable);

    /**
     * 獲取用戶預設地址
     */
    UserAddress getDefaultAddress(User user);

    /**
     * 根據ID獲取地址
     */
    UserAddress getAddressById(Long addressId, User user);

    /**
     * 檢查地址是否屬於用戶
     */
    boolean isAddressBelongsToUser(Long addressId, User user);

    /**
     * 統計用戶地址數量
     */
    long countUserAddresses(User user);
} 