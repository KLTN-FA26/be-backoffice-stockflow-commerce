package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JPA mapping of a customization template (table {@code product.customization_templates}): what a customer may print on
 * one variant. The database refuses an active template without a model ({@code ck_customization_templates_active_model}),
 * a second active template on a variant ({@code uk_customization_templates_active}), an upload type that is not a design
 * asset's MIME type, and a template on a product that is not CUSTOMIZABLE ({@code tg_customization_templates_kind}).
 *
 * <p>The model is stored as the JSON object the storefront reads ({@code {"kind": "parametric-cup", ...}}); mapping it
 * to {@code ProductModel} is the repository mapper's job, so this class stays a table mapping.</p>
 */
@Entity
@Table(name = "customization_templates", schema = "product")
public class CustomizationTemplateJpaEntity extends BaseEntity {

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "min_dpi", nullable = false)
    private int minDpi;

    @Column(name = "color_mode", nullable = false, length = 8)
    private String colorMode;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_file_formats", nullable = false, columnDefinition = "text[]")
    private String[] allowedFileFormats;

    @Column(name = "max_file_size_mb", nullable = false)
    private int maxFileSizeMb;

    @Column(name = "max_colors")
    private Integer maxColors;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_font_codes", columnDefinition = "text[]")
    private String[] allowedFontCodes;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model_params_json", columnDefinition = "jsonb")
    private Map<String, Object> modelParams;

    @Column(name = "reference_file_key", length = 500)
    private String referenceFileKey;

    @Column(name = "reference_file_name", length = 255)
    private String referenceFileName;

    @Column(name = "reference_content_type", length = 100)
    private String referenceContentType;

    @Column(name = "model_file_key", length = 500)
    private String modelFileKey;

    @Column(name = "model_file_name", length = 255)
    private String modelFileName;

    @Column(name = "model_file_checksum", length = 64)
    private String modelFileChecksum;

    /** {@code [{"printAreaCode": ..., "meshName": ...}]}; checked against the print areas by the service. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model_surfaces_json", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> modelSurfaces = List.of();

    protected CustomizationTemplateJpaEntity() {
    }

    public CustomizationTemplateJpaEntity(UUID id, UUID variantId, String code) {
        super(id);
        this.variantId = variantId;
        this.code = code;
    }

    public UUID getVariantId() { return variantId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public int getMinDpi() { return minDpi; }
    public String getColorMode() { return colorMode; }
    public String[] getAllowedFileFormats() { return allowedFileFormats; }
    public int getMaxFileSizeMb() { return maxFileSizeMb; }
    public Integer getMaxColors() { return maxColors; }
    public String[] getAllowedFontCodes() { return allowedFontCodes; }
    public boolean isActive() { return active; }
    public Map<String, Object> getModelParams() { return modelParams; }
    public String getReferenceFileKey() { return referenceFileKey; }
    public String getReferenceFileName() { return referenceFileName; }
    public String getReferenceContentType() { return referenceContentType; }
    public String getModelFileKey() { return modelFileKey; }
    public String getModelFileName() { return modelFileName; }
    public String getModelFileChecksum() { return modelFileChecksum; }
    public List<Map<String, Object>> getModelSurfaces() { return modelSurfaces; }

    /** Everything a Sales Manager edits at once; the model as its stored JSON object. */
    public void describe(String name, int minDpi, String colorMode, String[] allowedFileFormats, int maxFileSizeMb,
                         Integer maxColors, String[] allowedFontCodes, Map<String, Object> modelParams) {
        this.name = name;
        this.minDpi = minDpi;
        this.colorMode = colorMode;
        this.allowedFileFormats = allowedFileFormats;
        this.maxFileSizeMb = maxFileSizeMb;
        this.maxColors = maxColors;
        this.allowedFontCodes = allowedFontCodes;
        this.modelParams = modelParams;
    }

    public void setActive(boolean active) { this.active = active; }

    public void attachReference(String key, String fileName, String contentType) {
        this.referenceFileKey = key;
        this.referenceFileName = fileName;
        this.referenceContentType = contentType;
    }

    /** The {@code .glb} the model was measured from; {@code checksum} is its SHA-256 in hex. */
    public void attachModelFile(String key, String fileName, String checksum) {
        this.modelFileKey = key;
        this.modelFileName = fileName;
        this.modelFileChecksum = checksum;
    }

    public void mapModelSurfaces(List<Map<String, Object>> surfaces) {
        this.modelSurfaces = surfaces;
    }
}
