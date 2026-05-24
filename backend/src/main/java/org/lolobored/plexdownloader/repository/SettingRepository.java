package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<Setting, String> {}
