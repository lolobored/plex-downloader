package com.plexdownloader.repository;

import com.plexdownloader.model.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<Setting, String> {}
