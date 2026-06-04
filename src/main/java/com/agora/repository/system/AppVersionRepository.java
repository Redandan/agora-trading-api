package com.agora.repository.system;

import com.agora.model.AppVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 應用程式版本 Repository
 */
@Repository
public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {

    Optional<AppVersion> findByPlatformAndVersion(String platform, String version);

    List<AppVersion> findByPlatformOrderByVersionDesc(String platform);

    @Query("SELECT av FROM AppVersion av ORDER BY av.platform, av.version DESC")
    List<AppVersion> findAllActiveVersions();

    @Query("SELECT av FROM AppVersion av WHERE av.platform = :platform ORDER BY av.version DESC")
    List<AppVersion> findActiveVersionsByPlatform(@Param("platform") String platform);

    Optional<AppVersion> findByObjectName(String objectName);

    @Query("SELECT DISTINCT av.platform FROM AppVersion av")
    List<String> findAllPlatforms();
}

