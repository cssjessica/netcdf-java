/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.nc2.dataset;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import ucar.ma2.*;
import ucar.nc2.*;
import ucar.nc2.constants.CDM;
import ucar.nc2.constants.DataFormatType;
import ucar.nc2.dataset.NetcdfDataset.Enhance;
import ucar.nc2.filter.ConvertMissing;
import ucar.nc2.filter.FilterHelpers;
import ucar.nc2.filter.ScaleOffset;
import ucar.nc2.filter.Standardizer;
import ucar.nc2.filter.UnsignedConversion;
import ucar.nc2.internal.dataset.CoordinatesHelper;
import ucar.nc2.iosp.netcdf3.N3iosp;
import ucar.nc2.util.CancelTask;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

/**
 * A wrapper around a Variable, creating an "enhanced" Variable. The original Variable is used for the I/O.
 * There are several distinct uses:
 * <ol>
 * <li>Handle scale/offset/missing/enum/unsigned conversion; this can change DataType and data values</li>
 * <li>Container for coordinate system information</li>
 * <li>NcML modifications to underlying Variable</li>
 * </ol>
 *
 * @author caron
 * @see NetcdfDataset
 */
public class VariableDS extends Variable implements VariableEnhanced, EnhanceScaleMissingUnsigned {

  /**
   * Constructor when there's no underlying variable.
   * You must also set the values by doing one of:
   * <ol>
   * <li>set the values with setCachedData()</li>
   * <li>set a proxy reader with setProxyReader()</li>
   * </ol>
   * Otherwise, it is assumed to have constant values (using the fill value)
   *
   * @param ds the containing dataset
   * @param group the containing group
   * @param parentStructure the containing Structure (may be null)
   * @param shortName the (short) name
   * @param dataType the data type
   * @param dims list of dimension names, these must already exist in the Group; empty String = scalar
   * @param units String value of units, may be null
   * @param desc String value of description, may be null
   * @deprecated Use NetcdfDataset.builder()
   */
  @Deprecated
  public VariableDS(NetcdfDataset ds, Group group, Structure parentStructure, String shortName, DataType dataType,
      String dims, String units, String desc) {
    super(ds, group, parentStructure, shortName);
    setDataType(dataType);
    setDimensions(dims);
    this.orgDataType = dataType;

    if (dataType == DataType.STRUCTURE)
      throw new IllegalArgumentException("VariableDS must not wrap a Structure; name=" + shortName);

    if (units != null)
      addAttribute(new Attribute(CDM.UNITS, units.trim()));
    if (desc != null)
      addAttribute(new Attribute(CDM.LONG_NAME, desc));

    this.enhanceProxy = new EnhancementsImpl(this, units, desc);
  }

  /**
   * Make a new VariableDS, delegate data reading to the original variable, but otherwise
   * dont take any info from it. This is used by NcML explicit mode.
   *
   * @param group the containing group; may not be null
   * @param parent parent Structure, may be null
   * @param shortName variable shortName, must be unique within the Group
   * @param orgVar the original Variable to wrap. The original Variable is not modified.
   *        Must not be a Structure, use StructureDS instead.
   * @deprecated Use NetcdfDataset.builder()
   */
  @Deprecated
  public VariableDS(Group group, Structure parent, String shortName, Variable orgVar) {
    super(null, group, parent, shortName);
    setDimensions(getDimensionsString()); // reset the dimensions

    if (orgVar instanceof Structure)
      throw new IllegalArgumentException("VariableDS must not wrap a Structure; name=" + orgVar.getFullName());

    // dont share cache, iosp : all IO is delegated
    this.ncfile = null;
    this.spiObject = null;
    createNewCache();

    this.orgVar = orgVar;
    this.orgDataType = orgVar.getDataType();

    this.enhanceProxy = new EnhancementsImpl(this);
  }

  /**
   * Wrap the given Variable, making it into a VariableDS.
   * Delegate data reading to the original variable.
   * Take all metadata from original variable.
   * Does not share cache, iosp.
   *
   * @param g logical container, if null use orgVar's group
   * @param orgVar the original Variable to wrap. The original Variable is not modified.
   *        Must not be a Structure, use StructureDS instead.
   * @param enhance if true, use NetcdfDataset.defaultEnhanceMode to define what enhancements are made.
   *        Note that this can change DataType and data values.
   *        You can also call enhance() later. If orgVar is VariableDS, then enhance is inherited from there,
   *        and this parameter is ignored.
   * @deprecated Use NetcdfDataset.builder()
   */
  @Deprecated
  public VariableDS(Group g, Variable orgVar, boolean enhance) {
    super(orgVar);
    if (g != null)
      setParentGroup(g); // otherwise super() sets group; this affects the long name and the dimensions.
    setDimensions(getDimensionsString()); // reset the dimensions

    if (orgVar instanceof Structure)
      throw new IllegalArgumentException("VariableDS must not wrap a Structure; name=" + orgVar.getFullName());

    // dont share cache, iosp : all IO is delegated
    this.ncfile = null;
    this.spiObject = null;
    createNewCache();

    this.orgVar = orgVar;
    this.orgDataType = orgVar.getDataType();
    this.orgFileTypeId = orgVar.getFileTypeId();

    this.enhanceProxy = new EnhancementsImpl(this);
    if (enhance) {
      enhance(NetcdfDataset.getDefaultEnhanceMode());
    }

    // Add this so that old VariableDS units agrees with new VariableDS units.
    String units = orgVar.getUnitsString();
    if (units != null) {
      setUnitsString(units.trim());
    }
  }

  /**
   * Copy constructor, for subclasses.
   * Used by copy() and CoordinateAxis
   * Share everything except the coord systems.
   *
   * @param vds copy from here.
   * @param isCopy called from copy()
   * @deprecated Use NetcdfDataset.builder()
   */
  @Deprecated
  protected VariableDS(VariableDS vds, boolean isCopy) {
    super(vds);

    this.orgVar = vds;
    this.orgDataType = vds.orgDataType;
    this.orgName = vds.orgName;
    this.enhanceMode = vds.enhanceMode;

    this.enhanceProxy = new EnhancementsImpl(this); // decouple coordinate systems
    this.unsignedConversion = vds.unsignedConversion;
    this.scaleOffset = vds.scaleOffset;
    this.convertMissing = vds.convertMissing;
    this.fillValue = vds.getFillValue();
    this.hasFillValue = vds.hasFillValue();

    // Add this so that old VariableDS units agrees with new VariableDS units.
    String units = vds.getUnitsString();
    if (units != null) {
      setUnitsString(units.trim());
    }

    if (!isCopy) {
      createNewCache(); // dont share cache unless its a copy
    }
  }

  @Override
  public NetcdfFile getNetcdfFile() {
    // TODO can group really be null? Variable says no.
    return group == null ? null : group.getNetcdfFile();
  }

  // for section and slice
  /** @deprecated Use {@link #toBuilder()} */
  @Deprecated
  @Override
  protected VariableDS copy() {
    return new VariableDS(this, true);
  }

  /**
   * Remove coordinate system info.
   * 
   * @deprecated Use NetcdfDataset.builder()
   */
  @Deprecated
  @Override
  public void clearCoordinateSystems() {
    this.enhanceProxy = new EnhancementsImpl(this, getUnitsString(), getDescription());
  }

  /**
   * Calculate scale/offset/missing/enum/unsigned value info. This may change the DataType.
   * 
   * @deprecated Use NetcdfDataset.builder()
   */
  @Deprecated
  @Override
  public void enhance(Set<Enhance> enhancements) {
    this.enhanceMode = EnumSet.copyOf(enhancements);

    // this.enhanceMode will only contain enhancements not already applied to orgVar.
    if (orgVar instanceof VariableDS) {
      for (Enhance orgVarEnhancement : ((VariableDS) orgVar).getEnhanceMode()) {
        this.enhanceMode.remove(orgVarEnhancement);
      }
    }

    // enhance() may have been called previously, with a different enhancement set.
    // So, we need to reset to default before we process this new set.
    // LOOK this seems bogus
    if (orgDataType != null) {
      setDataType(orgDataType);
    }

    createEnhancements();
  }

  boolean needConvert() {
    Set<Enhance> enhancements = getEnhanceMode();
    return enhancements.contains(Enhance.ConvertEnums) || enhancements.contains(Enhance.ConvertUnsigned)
        || enhancements.contains(Enhance.ApplyScaleOffset) || enhancements.contains(Enhance.ConvertMissing);
  }

  Array convert(Array data) {
    return convert(data, enhanceMode);
  }

  Array convert(Array data, Set<NetcdfDataset.Enhance> enhancements) {
    if (enhancements.contains(Enhance.ConvertEnums)
        && (dataType.isEnum() || (orgDataType != null && orgDataType.isEnum()))) {
      // Creates STRING data. As a result, we can return here, because the other conversions don't apply to STRING.
      return convertEnums(data);
    } else {
      // TODO: make work for isVariableLength; i thought BUFR worked?
      if (this.isVariableLength) {
        return data;
      }
      if (enhancements.contains(Enhance.ConvertUnsigned) && unsignedConversion != null) {
        data = unsignedConversion.convertUnsigned(data);
      }
      if (enhancements.contains(Enhance.ApplyScaleOffset) && scaleOffset != null) {
        data = scaleOffset.removeScaleOffset(data);
      }
      if (enhancements.contains(Enhance.ConvertMissing) && convertMissing != null
          && (dataType == DataType.FLOAT || dataType == DataType.DOUBLE)) {
        data = convertMissing.convertMissing(data);
      }
      if (enhancements.contains(Enhance.ApplyStandardizer) && standardizer != null) {
        data = standardizer.convert(data);
      }
      return data;
    }
  }

  private Array convertEnums(Array values) {
    if (!values.getDataType().isIntegral()) {
      return values; // Nothing to do!
    }

    Array result = Array.factory(DataType.STRING, values.getShape());
    IndexIterator ii = result.getIndexIterator();
    values.resetLocalIterator();
    while (values.hasNext()) {
      String sval = lookupEnumString(values.nextInt());
      ii.setObjectNext(sval);
    }
    return result;
  }

  /**
   * Returns the enhancements applied to this variable. If this variable wraps another variable, the returned set will
   * also contain the enhancements applied to the nested variable, recursively.
   *
   * @return the enhancements applied to this variable.
   */
  public Set<Enhance> getEnhanceMode() {
    if (!(orgVar instanceof VariableDS)) {
      return Collections.unmodifiableSet(enhanceMode);
    } else {
      VariableDS orgVarDS = (VariableDS) orgVar;
      return Sets.union(enhanceMode, orgVarDS.getEnhanceMode());
    }
  }

  /**
   * Add {@code enhancement} to this variable. It may result in a change of data type.
   *
   * @param enhancement the enhancement to add.
   * @return {@code true} if the set of enhancements changed as a result of the call.
   * @deprecated Use NetcdfDataset.builder()
   */
  @Deprecated
  public boolean addEnhancement(Enhance enhancement) {
    if (enhanceMode.add(enhancement)) {
      enhance(enhanceMode);
      return true;
    } else {
      return false;
    }
  }

  /**
   * Remove {@code enhancement} from this variable. It may result in a change of data type.
   *
   * @param enhancement the enhancement to remove.
   * @return {@code true} if the set of enhancements changed as a result of the call.
   * @deprecated Use NetcdfDataset.builder()
   */
  @Deprecated
  public boolean removeEnhancement(Enhance enhancement) {
    if (enhanceMode.remove(enhancement)) {
      enhance(enhanceMode);
      return true;
    } else {
      return false;
    }
  }

  /**
   * A VariableDS usually wraps another Variable.
   *
   * @return original Variable or null
   */
  @Override
  public Variable getOriginalVariable() {
    return orgVar;
  }

  /**
   * Set the Variable to wrap. Used by NcML explicit mode.
   *
   * @param orgVar original Variable, must not be a Structure
   * @deprecated Use NetcdfDataset.builder()
   */
  @Deprecated
  @Override
  public void setOriginalVariable(Variable orgVar) {
    if (orgVar instanceof Structure)
      throw new IllegalArgumentException("VariableDS must not wrap a Structure; name=" + orgVar.getFullName());
    this.orgVar = orgVar;
  }

  /**
   * When this wraps another Variable, get the original Variable's DataType.
   *
   * @return original Variable's DataType, or current data type if it doesnt wrap another variable
   */
  public DataType getOriginalDataType() {
    return orgDataType != null ? orgDataType : getDataType();
  }

  /**
   * When this wraps another Variable, get the original Variable's name.
   *
   * @return original Variable's name
   */
  @Override
  public String getOriginalName() {
    return orgName;
  }

  @Override
  public String lookupEnumString(int val) {
    if (dataType.isEnum())
      return super.lookupEnumString(val);
    return orgVar.lookupEnumString(val);
  }

  /** @deprecated Use NetcdfDataset.builder() */
  @Deprecated
  @Override
  public String setName(String newName) {
    this.orgName = getShortName();
    super.setShortName(newName);
    return newName;
  }

  @Override
  public String toStringDebug() {
    return (orgVar != null) ? orgVar.toStringDebug() : "";
  }

  @Override
  public String getDatasetLocation() {
    String result = super.getDatasetLocation();
    if (result != null)
      return result;
    if (orgVar != null)
      return orgVar.getDatasetLocation();
    return null;
  }

  /** @deprecated Removed in v6 */
  @Deprecated
  public boolean hasCachedDataRecurse() {
    return super.hasCachedData() || ((orgVar != null) && orgVar.hasCachedData());
  }

  /** @deprecated Use NetcdfDataset.builder() */
  @Deprecated
  @Override
  public void setCaching(boolean caching) {
    if (caching && orgVar != null)
      orgVar.setCaching(true); // propagate down only if true
  }

  @Override
  protected Array _read() throws IOException {
    Array result;

    // check if already cached - caching in VariableDS only done explicitly by app
    if (hasCachedData())
      result = super._read();
    else
      result = proxyReader.reallyRead(this, null);

    return convert(result);
  }

  // section of regular Variable
  @Override
  protected Array _read(Section section) throws IOException, InvalidRangeException {
    // really a full read
    if ((null == section) || section.computeSize() == getSize())
      return _read();

    Array result;
    if (hasCachedData())
      result = super._read(section);
    else
      result = proxyReader.reallyRead(this, section, null);

    return convert(result);
  }

  // do not call directly
  @Override
  public Array reallyRead(Variable client, CancelTask cancelTask) throws IOException {
    if (orgVar == null)
      return getMissingDataArray(shape);

    return orgVar.read();
  }

  // do not call directly
  @Override
  public Array reallyRead(Variable client, Section section, CancelTask cancelTask)
      throws IOException, InvalidRangeException {
    // see if its really a full read
    if ((null == section) || section.computeSize() == getSize())
      return reallyRead(client, cancelTask);

    if (orgVar == null)
      return getMissingDataArray(section.getShape());

    return orgVar.read(section);
  }

  @Override
  public long readToStream(Section section, OutputStream out) throws IOException, InvalidRangeException {
    if (orgVar == null)
      return super.readToStream(section, out);

    return orgVar.readToStream(section, out);
  }

  /**
   * Return Array with missing data
   *
   * @param shape of this shape
   * @return Array with given shape
   */
  public Array getMissingDataArray(int[] shape) {
    Object storage;

    switch (getDataType()) {
      case BOOLEAN:
        storage = new boolean[1];
        break;
      case BYTE:
      case UBYTE:
      case ENUM1:
        storage = new byte[1];
        break;
      case CHAR:
        storage = new char[1];
        break;
      case SHORT:
      case USHORT:
      case ENUM2:
        storage = new short[1];
        break;
      case INT:
      case UINT:
      case ENUM4:
        storage = new int[1];
        break;
      case LONG:
      case ULONG:
        storage = new long[1];
        break;
      case FLOAT:
        storage = new float[1];
        break;
      case DOUBLE:
        storage = new double[1];
        break;
      default:
        storage = new Object[1];
    }

    Array array = Array.factoryConstant(getDataType(), shape, storage);
    if (hasFillValue) {
      array.setObject(0, fillValue);
    }
    return array;
  }

  /**
   * public for debugging
   *
   * @param f put info here
   */
  public void showScaleMissingProxy(Formatter f) {
    f.format("has missing = %s%n", convertMissing != null);
    if (convertMissing != null) {
      if (convertMissing.hasMissing()) {
        if (convertMissing.hasMissingValue()) {
          f.format("   missing value(s) = ");
          for (double d : convertMissing.getMissingValues())
            f.format(" %f", d);
          f.format("%n");
        }
        if (convertMissing.hasFillValue())
          f.format("   fillValue = %f%n", convertMissing.getFillValue());
        if (convertMissing.hasValidData())
          f.format("   valid min/max = [%f,%f]%n", convertMissing.getValidMin(), convertMissing.getValidMax());
      }
      f.format("FillValue or default = %s%n", convertMissing.getFillValue());
    }


    f.format("%nhas scale/offset = %s%n", scaleOffset != null);
    if (scaleOffset != null) {
      double offset = scaleOffset.applyScaleOffset(0.0);
      double scale = scaleOffset.applyScaleOffset(1.0) - offset;
      f.format("   scale_factor = %f add_offset = %f%n", scale, offset);
    }
    f.format("original data type = %s%n", orgDataType);
    f.format("converted data type = %s%n", getDataType());
  }

  ////////////////////////////////////////////// Enhancements //////////////////////////////////////////////

  @Override
  public String getDescription() {
    return enhanceProxy.getDescription();
  }

  @Override
  public String getUnitsString() {
    return enhanceProxy.getUnitsString();
  }

  /** @deprecated Use NetcdfDataset.builder() */
  @Deprecated
  @Override
  public void setUnitsString(String units) {
    enhanceProxy.setUnitsString(units);
  }

  @Override
  public ImmutableList<CoordinateSystem> getCoordinateSystems() {
    return enhanceProxy.getCoordinateSystems();
  }

  /** @deprecated Use VariableDS.builder() */
  @Deprecated
  @Override
  public void addCoordinateSystem(CoordinateSystem cs) {
    enhanceProxy.addCoordinateSystem(cs);
  }

  /** @deprecated Use VariableDS.builder() */
  @Deprecated
  @Override
  public void removeCoordinateSystem(CoordinateSystem cs) {
    enhanceProxy.removeCoordinateSystem(cs);
  }

  //////////////////////////////////////////// EnhanceScaleMissingUnsigned ////////////////////////////////////////////

  @Override
  public boolean hasScaleOffset() {
    return scaleOffset != null;
  }

  @Override
  public double getScaleFactor() {
    return scaleOffset != null ? scaleOffset.getScaleFactor() : 1;
  }

  @Override
  public double getOffset() {
    return scaleOffset != null ? scaleOffset.getOffset() : 0;
  }

  @Override
  public boolean hasMissing() {
    return convertMissing != null && convertMissing.hasMissing();
  }

  @Override
  public boolean isMissing(double val) {
    if (Double.isNaN(val)) {
      return true;
    }
    return convertMissing != null && convertMissing.isMissing(val);
  }

  @Override
  public boolean hasValidData() {
    return convertMissing != null && convertMissing.hasMissingValue();
  }

  @Override
  public double getValidMin() {
    return convertMissing != null ? convertMissing.getValidMin() : -Double.MAX_VALUE;
  }

  @Override
  public double getValidMax() {
    return convertMissing != null ? convertMissing.getValidMax() : Double.MAX_VALUE;
  }

  @Override
  public boolean isInvalidData(double val) {
    return convertMissing != null && convertMissing.isInvalidData(val);
  }

  @Override
  public boolean hasFillValue() {
    return hasFillValue;
  }

  @Override
  public double getFillValue() {
    return fillValue;
  }

  @Override
  public boolean isFillValue(double val) {
    return convertMissing != null && convertMissing.isFillValue(val);
  }

  @Override
  public boolean hasMissingValue() {
    return convertMissing != null && convertMissing.hasMissingValue();
  }

  @Override
  public double[] getMissingValues() {
    return convertMissing != null ? convertMissing.getMissingValues() : new double[] {0};
  }

  @Override
  public boolean isMissingValue(double val) {
    return convertMissing != null && convertMissing.isMissingValue(val);
  }

  /** @deprecated Use NetcdfDataset.builder() */
  @Deprecated
  @Override
  public void setFillValueIsMissing(boolean b) {
    if (convertMissing != null) {
      convertMissing.setFillValueIsMissing(b);
    }
  }

  /** @deprecated Use NetcdfDataset.builder() */
  @Deprecated
  @Override
  public void setInvalidDataIsMissing(boolean b) {
    if (convertMissing != null) {
      convertMissing.setInvalidDataIsMissing(b);
    }
  }

  public boolean missingDataIsMissing() {
    return builder().missingDataIsMissing;
  }

  public boolean fillValueIsMissing() {
    return builder().fillValueIsMissing;
  }

  public boolean invalidDataIsMissing() {
    return builder().invalidDataIsMissing;
  }

  /** @deprecated Use NetcdfDataset.builder() */
  @Deprecated
  @Override
  public void setMissingDataIsMissing(boolean b) {
    if (convertMissing != null) {
      convertMissing.setMissingDataIsMissing(b);
    }
  }

  @Nullable
  @Override
  public DataType getScaledOffsetType() {
    return scaleOffset != null ? scaleOffset.getScaledOffsetType() : dataType;
  }

  @Override
  public DataType getUnsignedConversionType() {
    return unsignedConversion != null ? unsignedConversion.getOutType() : dataType;
  }

  @Override
  public DataType.Signedness getSignedness() {
    return unsignedConversion != null ? unsignedConversion.getSignedness() : dataType.getSignedness();
  }

  @Override
  public double applyScaleOffset(Number value) {
    return scaleOffset != null ? scaleOffset.removeScaleOffset(value) : value.doubleValue();
  }

  @Override
  public Array applyScaleOffset(Array data) {
    return scaleOffset != null ? scaleOffset.removeScaleOffset(data) : data;
  }

  @Override
  public Number convertUnsigned(Number value) {
    return unsignedConversion != null ? unsignedConversion.convertUnsigned(value) : value;
  }

  public Number convertUnsigned(Number value, DataType dataType) {
    return unsignedConversion != null ? unsignedConversion.convertUnsigned(value, dataType) : value;
  }

  @Override
  public Array convertUnsigned(Array in) {
    return unsignedConversion != null ? unsignedConversion.convertUnsigned(in) : in;
  }

  @Override
  public Number convertMissing(Number value) {
    return convertMissing != null ? convertMissing.convertMissing(value) : value;
  }

  @Override
  public Array convertMissing(Array in) {
    return (convertMissing != null && (dataType == DataType.FLOAT || dataType == DataType.DOUBLE))
        ? convertMissing.convertMissing(in)
        : in;
  }

  /**
   * @deprecated use implementations in filters package
   */
  @Override
  @Deprecated
  public Array convert(Array in, boolean convertUnsigned, boolean applyScaleOffset, boolean convertMissing) {
    return convertMissing(applyScaleOffset(convertUnsigned(in)));
  }

  ////////////////////////////////////////////////////////////////////////////////////////////
  // TODO remove in version 6.
  private EnhancementsImpl enhanceProxy;
  private List<String> coordSysNames;

  // TODO make immutable in version 6
  private UnsignedConversion unsignedConversion;
  private ScaleOffset scaleOffset;
  private Standardizer standardizer;
  private ConvertMissing convertMissing;
  private Set<Enhance> enhanceMode = EnumSet.noneOf(Enhance.class); // The set of enhancements that were made.

  protected Variable orgVar; // wrap this Variable : use it for the I/O
  protected DataType orgDataType; // keep separate for the case where there is no orgVar.
  protected String orgName; // in case Variable was renamed, and we need to keep track of the original name
  String orgFileTypeId; // the original fileTypeId.

  private boolean hasFillValue = false;
  private double fillValue = Double.MAX_VALUE;

  protected VariableDS(Builder<?> builder, Group parentGroup) {
    super(builder, parentGroup);

    this.enhanceMode = builder.enhanceMode;
    this.orgVar = builder.orgVar;
    this.orgDataType = builder.orgDataType;
    this.orgName = builder.orgName;
    if (this.orgDataType == null) {
      this.orgDataType = dataType;
    }

    // Make sure that units has been trimmed.
    // Replace with correct case
    // TODO Can simplify when doesnt have to agree with old VariableDS
    Attribute units = builder.getAttributeContainer().findAttributeIgnoreCase(CDM.UNITS);
    if (units != null && units.isString()) {
      builder.getAttributeContainer()
          .addAttribute(Attribute.builder(CDM.UNITS).setStringValue(units.getStringValue().trim()).build());
    }

    this.orgFileTypeId = builder.orgFileTypeId;
    this.enhanceProxy = new EnhancementsImpl(this, builder.units, builder.getDescription());

    createEnhancements();

    // We have to complete this after the NetcdfDataset is built.
    this.coordSysNames = builder.coordSysNames;
  }

  private void createEnhancements() {
    // Enhancement Objects are created if it is contained in Enhance
    if (this.enhanceMode.contains(Enhance.ConvertEnums) && dataType.isEnum()) {
      this.dataType = DataType.STRING;
    }

    if (this.enhanceMode.contains(Enhance.ConvertUnsigned)) {
      this.unsignedConversion = UnsignedConversion.createFromVar(this);
      this.dataType = unsignedConversion != null ? unsignedConversion.getOutType() : dataType;
    }
    if (this.enhanceMode.contains(Enhance.ApplyScaleOffset) && (dataType.isNumeric() || dataType == DataType.CHAR)) {
      this.scaleOffset = ScaleOffset.createFromVariable(this);
      this.dataType = scaleOffset != null ? scaleOffset.getScaledOffsetType() : this.dataType;
    }
    Attribute standardizerAtt = findAttribute(CDM.STANDARDIZE);
    if (standardizerAtt != null && this.enhanceMode.contains(Enhance.ApplyStandardizer) && dataType.isFloatingPoint()) {
      this.standardizer = Standardizer.createFromVariable(this);
    }

    // need fill value info before convertMissing
    Attribute fillValueAtt = findAttribute(CDM.FILL_VALUE);
    if (fillValueAtt != null && !fillValueAtt.isString()) {
      DataType fillType = FilterHelpers.getAttributeDataType(fillValueAtt, getSignedness());
      fillValue = applyScaleOffset(convertUnsigned(fillValueAtt.getNumericValue(), fillType).doubleValue());
      hasFillValue = true;
    } else {
      // No _FillValue attribute found. Instead, if file is NetCDF and variable is numeric, use the default fill value.
      boolean isNetcdfIosp = DataFormatType.NETCDF.getDescription().equals(orgFileTypeId)
          || DataFormatType.NETCDF4.getDescription().equals(orgFileTypeId);

      if (isNetcdfIosp) {
        if (dataType.isNumeric()) {
          fillValue = applyScaleOffset(N3iosp.getFillValueDefault(dataType));
          hasFillValue = true;
        }
      }
    }
    if (this.enhanceMode.contains(Enhance.ConvertMissing)) {
      this.convertMissing = ConvertMissing.createFromVariable(this);
    }
  }

  public Builder<?> toBuilder() {
    return addLocalFieldsToBuilder(builder());
  }

  // Add local fields to the passed - in builder.
  protected Builder<?> addLocalFieldsToBuilder(Builder<? extends Builder<?>> builder) {
    builder.setOriginalVariable(this.orgVar).setOriginalDataType(this.orgDataType).setOriginalName(this.orgName)
        .setOriginalFileTypeId(this.orgFileTypeId).setEnhanceMode(this.enhanceMode).setUnits(this.enhanceProxy.units)
        .setDesc(this.enhanceProxy.desc);

    return (VariableDS.Builder<?>) super.addLocalFieldsToBuilder(builder);
  }

  /** @deprecated do not use */
  @Deprecated
  void setCoordinateSystems(CoordinatesHelper coords) {
    for (String name : this.coordSysNames) {
      coords.findCoordSystem(name).ifPresent(cs -> this.enhanceProxy.addCoordinateSystem(cs));
    }
  }

  /**
   * Get Builder for this class that allows subclassing.
   * 
   * @see "https://community.oracle.com/blogs/emcmanus/2010/10/24/using-builder-pattern-subclasses"
   */
  public static Builder<?> builder() {
    return new Builder2();
  }

  private static class Builder2 extends Builder<Builder2> {
    @Override
    protected Builder2 self() {
      return this;
    }
  }

  public static abstract class Builder<T extends Builder<T>> extends Variable.Builder<T> {
    public Set<Enhance> enhanceMode = EnumSet.noneOf(Enhance.class);
    public Variable orgVar; // wrap this Variable : use it for the I/O
    public DataType orgDataType; // keep separate for the case where there is no orgVar.
    public String orgFileTypeId; // the original fileTypeId.
    String orgName; // in case Variable was renamed, and we need to keep track of the original name
    private String units;
    private String desc;
    public List<String> coordSysNames = new ArrayList<>();

    private boolean invalidDataIsMissing = NetcdfDataset.invalidDataIsMissing;
    private boolean fillValueIsMissing = NetcdfDataset.fillValueIsMissing;
    private boolean missingDataIsMissing = NetcdfDataset.missingDataIsMissing;

    private boolean built;

    protected abstract T self();

    public T setEnhanceMode(Set<Enhance> enhanceMode) {
      this.enhanceMode = enhanceMode;
      return self();
    }

    public T addEnhanceMode(Set<Enhance> enhanceMode) {
      this.enhanceMode.addAll(enhanceMode);
      return self();
    }

    public T setOriginalVariable(Variable orgVar) {
      this.orgVar = orgVar;
      return self();
    }

    public T setOriginalDataType(DataType orgDataType) {
      this.orgDataType = orgDataType;
      return self();
    }

    public T setOriginalName(String orgName) {
      this.orgName = orgName;
      return self();
    }

    public T setOriginalFileTypeId(String orgFileTypeId) {
      this.orgFileTypeId = orgFileTypeId;
      return self();
    }

    public T setUnits(String units) {
      this.units = units;
      if (units != null) {
        this.units = units.trim();
        addAttribute(new Attribute(CDM.UNITS, this.units));
      }
      return self();
    }

    public T setDesc(String desc) {
      this.desc = desc;
      if (desc != null) {
        addAttribute(new Attribute(CDM.LONG_NAME, desc));
      }
      return self();
    }

    public void addCoordinateSystemName(String coordSysName) {
      coordSysNames.add(coordSysName);
    }

    public void setFillValueIsMissing(boolean b) {
      this.fillValueIsMissing = b;
    }

    public void setInvalidDataIsMissing(boolean b) {
      this.invalidDataIsMissing = b;
    }

    public void setMissingDataIsMissing(boolean b) {
      this.missingDataIsMissing = b;
    }

    /** Copy of this builder. */
    @Override
    public Variable.Builder<?> copy() {
      return new Builder2().copyFrom(this);
    }

    /** Copy metadata from orgVar. */
    @Override
    public T copyFrom(Variable orgVar) {
      super.copyFrom(orgVar);
      setSPobject(null);
      // resetCache();
      setOriginalVariable(orgVar);
      setOriginalDataType(orgVar.getDataType());
      setOriginalName(orgVar.getShortName());

      this.orgFileTypeId = orgVar.getFileTypeId();
      return self();
    }

    public T copyFrom(VariableDS.Builder<?> builder) {
      super.copyFrom(builder);

      builder.coordSysNames.forEach(name -> this.addCoordinateSystemName(name));
      setDesc(builder.desc);
      setEnhanceMode(builder.enhanceMode);
      setFillValueIsMissing(builder.fillValueIsMissing);
      setInvalidDataIsMissing(builder.invalidDataIsMissing);
      setMissingDataIsMissing(builder.missingDataIsMissing);
      this.orgVar = builder.orgVar;
      this.orgDataType = builder.orgDataType;
      this.orgFileTypeId = builder.orgFileTypeId;
      this.orgName = builder.orgName;
      setUnits(builder.units);

      return self();
    }

    public String getUnits() {
      String result = units;
      if (result == null) {
        result = getAttributeContainer().findAttributeString(CDM.UNITS, null);
      }
      if (result == null && orgVar != null) {
        result = orgVar.attributes().findAttributeString(CDM.UNITS, null);
      }
      return (result == null) ? null : result.trim();
    }

    public String getDescription() {
      String result = desc;
      if (result == null) {
        result = getAttributeContainer().findAttributeString(CDM.LONG_NAME, null);
      }
      if (result == null && orgVar != null) {
        result = orgVar.attributes().findAttributeString(CDM.LONG_NAME, null);
      }
      return (result == null) ? null : result.trim();
    }

    /** Normally this is called by Group.build() */
    public VariableDS build(Group parentGroup) {
      if (built)
        throw new IllegalStateException("already built");
      built = true;
      return new VariableDS(this, parentGroup);
    }
  }
}
