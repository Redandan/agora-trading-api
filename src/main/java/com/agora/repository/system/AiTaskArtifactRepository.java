package com.agora.repository.system;

import com.agora.model.AiTaskArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiTaskArtifactRepository extends JpaRepository<AiTaskArtifact, Long> {

    List<AiTaskArtifact> findByTaskIdOrderByCreatedAtAsc(Long taskId);
}
