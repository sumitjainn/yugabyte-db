// Copyright (c) YugaByte, Inc.
package com.yugabyte.yw.models;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.yugabyte.yw.cloud.PublicCloudConstants;
import com.yugabyte.yw.commissioner.Common;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.Model;
import com.avaje.ebean.SqlUpdate;
import com.avaje.ebean.annotation.EnumValue;

import play.data.validation.Constraints;
import play.libs.Json;

@Entity
public class InstanceType extends Model {
  public static final Logger LOG = LoggerFactory.getLogger(InstanceType.class);
  public enum VolumeType {
    @EnumValue("EBS")
    EBS,

    @EnumValue("SSD")
    SSD,

    @EnumValue("HDD")
    HDD
  }

  @EmbeddedId
  @Constraints.Required
  public InstanceTypeKey idKey;

  public String getProviderCode() { return this.idKey.providerCode; }

  public String getInstanceTypeCode() { return this.idKey.instanceTypeCode; }

  @Constraints.Required
  @Column(nullable = false, columnDefinition = "boolean default true")
  private Boolean active = true;
  public Boolean isActive() { return active; }
  public void setActive(Boolean active) { this.active = active; }

  @Constraints.Required
  @Column(nullable = false)
  public Integer numCores;

  @Constraints.Required
  @Column(nullable = false)
  public Double memSizeGB;

  @Column(columnDefinition = "TEXT")
  private String instanceTypeDetailsJson;
  public InstanceTypeDetails instanceTypeDetails;

  private static final Find<InstanceTypeKey, InstanceType> find =
    new Find<InstanceTypeKey, InstanceType>() {};

  public static InstanceType get(String providerCode, String instanceTypeCode) {
    InstanceType instanceType = find.byId(InstanceTypeKey.create(instanceTypeCode, providerCode));
    if (instanceType == null) {
      return instanceType;
    }
    // Since 'instanceTypeDetailsJson' can be null (populated externally), we need to populate these
    // fields explicitly.
    if (instanceType.instanceTypeDetailsJson == null ||
      instanceType.instanceTypeDetailsJson.isEmpty()) {
      instanceType.instanceTypeDetails = new InstanceTypeDetails();
      instanceType.instanceTypeDetailsJson =
        Json.stringify(Json.toJson(instanceType.instanceTypeDetails));
    } else {
      instanceType.instanceTypeDetails =
        Json.fromJson(Json.parse(instanceType.instanceTypeDetailsJson), InstanceTypeDetails.class);
    }
    return instanceType;
  }

  public static InstanceType upsert(String providerCode,
                                    String instanceTypeCode,
                                    Integer numCores,
                                    Double memSize,
                                    InstanceTypeDetails instanceTypeDetails) {
    InstanceType instanceType = InstanceType.get(providerCode, instanceTypeCode);
    if (instanceType == null) {
      instanceType = new InstanceType();
      instanceType.idKey = InstanceTypeKey.create(instanceTypeCode, providerCode);
    }
    instanceType.memSizeGB = memSize;
    instanceType.numCores = numCores;
    instanceType.instanceTypeDetails = instanceTypeDetails;
    instanceType.instanceTypeDetailsJson = Json.stringify(Json.toJson(instanceTypeDetails));
    // Update the in-memory fields.
    instanceType.save();
    // Update the JSON field - this does not seem to be updated by the save above.
    String updateQuery = "UPDATE instance_type " +
      "SET instance_type_details_json = :instanceTypeDetails " +
      "WHERE provider_code = :providerCode AND instance_type_code = :instanceTypeCode";
    SqlUpdate update = Ebean.createSqlUpdate(updateQuery);
    update.setParameter("instanceTypeDetails", instanceType.instanceTypeDetailsJson);
    update.setParameter("providerCode", providerCode);
    update.setParameter("instanceTypeCode", instanceTypeCode);
    int modifiedCount = Ebean.execute(update);
    // Check if the save was not successful.
    if (modifiedCount == 0) {
      // Throw an exception as the save was not successful.
      LOG.error("Failed to update SQL row");
    } else if (modifiedCount > 1) {
      // Exactly one row should have been modified.
      LOG.error("Running query [" + updateQuery + "] updated " + modifiedCount + " rows");
    }
    return instanceType;
  }

  /**
   * Reset the 'instance_type_details_json' of all rows belonging to a specific provider in this table.
   */
  public static void resetInstanceTypeDetailsForProvider(Common.CloudType providerCode) {
    String updateQuery = "UPDATE instance_type SET instance_type_details_json = '' WHERE provider_code = :providerCode";
    SqlUpdate update = Ebean.createSqlUpdate(updateQuery).setParameter("providerCode", providerCode.name());
    int modifiedCount = Ebean.execute(update);
    LOG.info("Query [" + updateQuery + "] updated " + modifiedCount + " rows");
    if (modifiedCount == 0) {
      LOG.warn("Failed to update any SQL row");
    }
  }

  /**
   * Delete Instance Types corresponding to given provider
   */
  public static void deleteInstanceTypesForProvider(Provider provider) {
    for (InstanceType instanceType : findByProvider(provider)) {
      instanceType.delete();
    }
  }

  /**
   * Query Helper to find supported instance types for a given cloud provider.
   */
  public static List<InstanceType> findByProvider(Provider provider) {
    List<InstanceType> entries = InstanceType.find.where().eq("provider_code", provider.code)
        .findList();
    return entries.stream().map(entry -> InstanceType.get(entry.getProviderCode(),
        entry.getInstanceTypeCode())).collect(Collectors.toList());
  }

  public static InstanceType createWithMetadata(Provider provider, String instanceTypeCode,
                                                JsonNode metadata) {
    return upsert(provider.code,
        instanceTypeCode,
        Integer.parseInt(metadata.get("numCores").toString()),
        Double.parseDouble(metadata.get("memSizeGB").toString()),
        Json.fromJson(metadata.get("instanceTypeDetails"), InstanceTypeDetails.class));
  }

  /**
   * Default details for volumes attached to this instance.
   */
  public static class VolumeDetails {
    public Integer volumeSizeGB;
    public VolumeType volumeType;
    public String mountPath;
  }

  public static class InstanceTypeDetails {
  	public static final int DEFAULT_VOLUME_COUNT = 2;
  	public static final int DEFAULT_VOLUME_SIZE_GB = 250;

    public List<VolumeDetails> volumeDetailsList;
    public PublicCloudConstants.Tenancy tenancy;

    public InstanceTypeDetails() {
      volumeDetailsList = new LinkedList<>();
    }

    public void setVolumeDetailsList(int volumeCount, int volumeSizeGB, VolumeType volumeType) {
      for (int i = 0; i < volumeCount; i++) {
        VolumeDetails volumeDetails = new VolumeDetails();
        volumeDetails.volumeSizeGB = volumeSizeGB;
        volumeDetails.volumeType = volumeType;
        volumeDetailsList.add(volumeDetails);
      }
      setDefaultMountPaths();
    }

    public void setDefaultMountPaths() {
      for (int idx = 0; idx < volumeDetailsList.size(); ++idx) {
        volumeDetailsList.get(idx).mountPath = String.format("/mnt/d%d", idx);
      }
    }
    
    public static InstanceTypeDetails createDefault() {
    	InstanceTypeDetails instanceTypeDetails = new InstanceTypeDetails();
    	instanceTypeDetails.setVolumeDetailsList(DEFAULT_VOLUME_COUNT, DEFAULT_VOLUME_SIZE_GB,
          VolumeType.EBS);
    	return instanceTypeDetails;
    }
    
  }
}
