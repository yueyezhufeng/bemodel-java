package com.bemodel.cs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义问数场景数据源清单(双演示库收口,2026-09-26):
 * 物理映射段、表列白名单、口径卡候选只认清单内数据源——问数与对账/巡检共用一个语义世界。
 * 清单为空 = 不过滤 = 与收口前行为一致(红线,SceneOffBehaviorTest 钉死)。
 */
@Data
@Component
@ConfigurationProperties(prefix = "bemodel.semantic")
public class SemanticSceneProperties {

    /** 进语义问数的数据源编码;默认=仿真试点三库(application.yml) */
    private List<String> sceneDs = new ArrayList<>();

    /** 是否启用过滤(空清单=关闭) */
    public boolean filtering() {
        return sceneDs != null && !sceneDs.isEmpty();
    }

    /** 数据源是否在场景内;过滤关闭时恒 true(旧行为),dsCode 空白视为不在场 */
    public boolean inScene(String dsCode) {
        if (!filtering()) {
            return true;
        }
        return dsCode != null && sceneDs.stream().anyMatch(s -> dsCode.trim().equals(s.trim()));
    }
}
