/*
 * Copyright (C) 2015 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.data.utilities;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.opendatakit.database.LocalKeyValueStoreConstants;
import org.opendatakit.database.RoleConsts;
import org.opendatakit.aggregate.odktables.rest.ElementDataType;
import org.opendatakit.aggregate.odktables.rest.KeyValueStoreConstants;
import org.opendatakit.application.CommonApplication;
import org.opendatakit.database.data.ColumnDefinition;
import org.opendatakit.database.data.OrderedColumns;
import org.opendatakit.data.TableViewType;
import org.opendatakit.database.utilities.KeyValueStoreUtils;
import org.opendatakit.exception.ServicesAvailabilityException;
import org.opendatakit.logging.WebLogger;
import org.opendatakit.properties.CommonToolProperties;
import org.opendatakit.properties.PropertiesSingleton;
import org.opendatakit.utilities.LocalizationUtils;
import org.opendatakit.utilities.NameUtil;
import org.opendatakit.utilities.ODKFileUtils;
import org.opendatakit.utilities.StaticStateManipulator;
import org.opendatakit.utilities.StaticStateManipulator.IStaticFieldManipulator;
import org.opendatakit.database.data.KeyValueStoreEntry;
import org.opendatakit.database.service.DbHandle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TableUtil {


  // INDEX_COL is held fixed during left/right pan

  /***********************************
   * Default values for those keys which require them. TODO When the keys in the
   * KVS are moved to the respective classes that use them, these should go
   * there most likely.
   ***********************************/
  public static final String DEFAULT_KEY_SORT_ORDER = "ASC";
  public static final TableViewType DEFAULT_KEY_CURRENT_VIEW_TYPE = TableViewType.SPREADSHEET;

  public static class MapViewColorRuleInfo {
    /**
     * One of:
     * LocalKeyValueStoreConstants.Map.COLOR_TYPE_NONE
     * LocalKeyValueStoreConstants.Map.COLOR_TYPE_TABLE
     * LocalKeyValueStoreConstants.Map.COLOR_TYPE_STATUS
     * LocalKeyValueStoreConstants.Map.COLOR_TYPE_COLUMN
     */
    public final String colorType;
    /**
     * elementKey of column to use for coloring map markers
     * if colorType is LocalKeyValueStoreConstants.Map.COLOR_TYPE_COLUMN
     */
    public final String colorElementKey;

    public MapViewColorRuleInfo(String colorType, String colorElementKey) {
      this.colorType = colorType;
      this.colorElementKey = colorElementKey;
    }
  }

  public static class TableColumns {
    public final OrderedColumns orderedDefns;
    public final String[] adminColumns;
    public final Map<String,String> localizedDisplayNames;
    
    TableColumns(OrderedColumns orderedDefns, String[] adminColumns, Map<String,String> localizedDisplayNames) {
      this.orderedDefns = orderedDefns;
      this.adminColumns = adminColumns;
      this.localizedDisplayNames = localizedDisplayNames;
    }
  }

  private static TableUtil tableUtil = new TableUtil();
  
  static {
    // register a state-reset manipulator for 'tableUtil' field.
    StaticStateManipulator.get().register(50, new IStaticFieldManipulator() {

      @Override
      public void reset() {
        tableUtil = new TableUtil();
      }
      
    });
  }

  public static TableUtil get() {
    return tableUtil;
  }
 
  /**
   * For mocking -- supply a mocked object.
   * 
   * @param util
   */
  public static void set(TableUtil util) {
    tableUtil = util;
  }
  
  protected TableUtil() {}

  /**
   * Determine whether table is locked or not
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return true if locked. False otherwise.
   * @throws ServicesAvailabilityException
   */
  public boolean isTableLocked(CommonApplication ctxt, String appName, DbHandle db,
      String tableId) throws
      ServicesAvailabilityException {

    List<KeyValueStoreEntry> lockedList =
        ctxt.getDatabase().getTableMetadata(appName, db, tableId,
            KeyValueStoreConstants.PARTITION_TABLE,
            LocalKeyValueStoreConstants.TableSecurity.ASPECT,
            LocalKeyValueStoreConstants.TableSecurity.KEY_LOCKED, null).getEntries();
    if (lockedList.size() == 0) {
      return false; // not locked
    }
    if ( lockedList.size() != 1 ) {
      throw new IllegalStateException("should be impossible");
    }
    Boolean outcome = KeyValueStoreUtils.getBoolean(appName, lockedList.get(0));
    return outcome;
  }

  /**
   * Determine whether user can add a row to the table
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return true if locked. False otherwise.
   * @throws ServicesAvailabilityException
   */
  public boolean canAddRowToTable(CommonApplication ctxt, String appName, DbHandle db,
      String tableId) throws
      ServicesAvailabilityException {

    String rolesList = ctxt.getDatabase().getRolesList(appName);

    if ( rolesList != null &&
        ( rolesList.contains(RoleConsts.ROLE_ADMINISTRATOR) ||
          rolesList.contains(RoleConsts.ROLE_SUPER_USER)) ) {
      return true;
    }

    if ( isTableLocked(ctxt, appName, db, tableId) ) {
      return false;
    }

    if ( rolesList != null && rolesList.contains(RoleConsts.ROLE_USER) ) {
      return true;
    }

    List<KeyValueStoreEntry> anonAddList = ctxt.getDatabase()
          .getTableMetadata(appName, db, tableId, KeyValueStoreConstants.PARTITION_TABLE,
              LocalKeyValueStoreConstants.TableSecurity.ASPECT,
              LocalKeyValueStoreConstants.TableSecurity.KEY_UNVERIFIED_USER_CAN_CREATE, null)
        .getEntries();
    if (anonAddList.size() == 0) {
      return true; // yes if unspecified
    }
    if ( anonAddList.size() != 1 ) {
      throw new IllegalStateException("should be impossible");
    }
    Boolean outcome = KeyValueStoreUtils.getBoolean(appName, anonAddList.get(0));
    return outcome;
  }

  /**
   * Commonly useful wrapper for getRawDisplayName
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return
   * @throws ServicesAvailabilityException
   */
  public String getLocalizedDisplayName(CommonApplication ctxt, String appName, DbHandle db, String tableId) throws
      ServicesAvailabilityException {

    String rawDisplayName = getRawDisplayName(ctxt, appName, db, tableId);
    String displayName = null;
    if ( rawDisplayName != null ) {
      displayName = LocalizationUtils.getLocalizedDisplayName(rawDisplayName);
    }
    if ( displayName == null ) {
      displayName = NameUtil.constructSimpleDisplayName(tableId);
    }
    return displayName;
  }

  /**
   * Obtain the displayName construction for the table from the database.
   * Supply a useful default value if the databse does not contain one.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return
   * @throws ServicesAvailabilityException
   */
  public String getRawDisplayName(CommonApplication ctxt, String appName, DbHandle db, String tableId) throws ServicesAvailabilityException {

    List<KeyValueStoreEntry> displayNameList =
            ctxt.getDatabase().getTableMetadata(appName, db, tableId,
                    KeyValueStoreConstants.PARTITION_TABLE, KeyValueStoreConstants.ASPECT_DEFAULT,
                KeyValueStoreConstants.TABLE_DISPLAY_NAME, null).getEntries();
    if (displayNameList.size() != 1) {
      return NameUtil.normalizeDisplayName(NameUtil.constructSimpleDisplayName(tableId));
    }
    String jsonDisplayName = displayNameList.get(0).value;
    if ( jsonDisplayName == null ) {
      jsonDisplayName = NameUtil.normalizeDisplayName(NameUtil.constructSimpleDisplayName(tableId));
    }
    return jsonDisplayName;
  }

  /**
   * Sets the table's raw displayName.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @param rawDisplayName
   * @throws ServicesAvailabilityException
   */
  public void setRawDisplayName( CommonApplication ctxt, String appName, DbHandle db, String tableId, String rawDisplayName) throws ServicesAvailabilityException {
    KeyValueStoreEntry e = KeyValueStoreUtils.buildEntry(tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            KeyValueStoreConstants.TABLE_DISPLAY_NAME,
            ElementDataType.object, rawDisplayName);
    ctxt.getDatabase().replaceTableMetadata(appName, db, e);
  }

  /**
   * Set the raw display name and return the value that was stored.
   *
   * @param ctxt
   * @param appName
   * @param tableId
   * @param rawDisplayName
   * @return
   * @throws ServicesAvailabilityException
   */
  public String atomicSetRawDisplayName( CommonApplication ctxt, String appName, String tableId, String rawDisplayName) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      setRawDisplayName(ctxt, appName, db, tableId, rawDisplayName);
      if ( rawDisplayName == null || rawDisplayName.length() == 0 ) {
        rawDisplayName = NameUtil.normalizeDisplayName(NameUtil.constructSimpleDisplayName(tableId));
      }
      return rawDisplayName;
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  /**
   * Get the default view type for this table.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return the specified default view type or SPREADSHEET_VIEW if none defined.
   * @throws ServicesAvailabilityException 
   */
  public TableViewType getDefaultViewType( CommonApplication ctxt, String appName, DbHandle db, String tableId) throws ServicesAvailabilityException {

    List<KeyValueStoreEntry> kvsList =
            ctxt.getDatabase().getTableMetadata(appName, db, tableId,
                    KeyValueStoreConstants.PARTITION_TABLE,
                    KeyValueStoreConstants.ASPECT_DEFAULT,
                LocalKeyValueStoreConstants.Tables.TABLE_DEFAULT_VIEW_TYPE, null).getEntries();
    if (kvsList.size() != 1) {
      return DEFAULT_KEY_CURRENT_VIEW_TYPE;
    }
    String rawViewType = kvsList.get(0).value;
    if ( rawViewType == null ) {
      return DEFAULT_KEY_CURRENT_VIEW_TYPE;
    }
    try {
      TableViewType tvt = TableViewType.valueOf(rawViewType);
      return tvt;
    } catch (Exception e) {
      return DEFAULT_KEY_CURRENT_VIEW_TYPE;
    }
  }

  /**
   * Sets the table's default view type
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @param viewType
   * @throws ServicesAvailabilityException
   */
  public void setDefaultViewType( CommonApplication ctxt, String appName, DbHandle db, String tableId, TableViewType viewType) throws ServicesAvailabilityException {
    KeyValueStoreEntry e = KeyValueStoreUtils.buildEntry(tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            LocalKeyValueStoreConstants.Tables.TABLE_DEFAULT_VIEW_TYPE,
            ElementDataType.string, viewType.name());
    ctxt.getDatabase().replaceTableMetadata(appName, db, e);
  }

  /**
   * Wrapper to handle database interactions to setDefaultViewType
   *
   * @param ctxt
   * @param appName
   * @param tableId
   * @param viewType
   * @throws ServicesAvailabilityException
   */
  public void atomicSetDefaultViewType( CommonApplication ctxt, String appName, String tableId, TableViewType viewType) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      setDefaultViewType(ctxt, appName, db, tableId, viewType);
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  /**
   * Get the filename for the detail view of this table.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return null if none defined.
   * @throws ServicesAvailabilityException 
   */
  public String getDetailViewFilename( CommonApplication ctxt, String appName, DbHandle db, String tableId) throws ServicesAvailabilityException {

    // TODO: this should probably use the detailView name as the aspect
    List<KeyValueStoreEntry> kvsList =
            ctxt.getDatabase().getTableMetadata(appName, db, tableId,
                KeyValueStoreConstants.PARTITION_TABLE, KeyValueStoreConstants.ASPECT_DEFAULT,
                LocalKeyValueStoreConstants.Tables.KEY_DETAIL_VIEW_FILE_NAME, null).getEntries();
    if (kvsList.size() != 1) {
      return null;
    }
    String rawValue = KeyValueStoreUtils.getString(appName, kvsList.get(0));
    return rawValue;
  }

  /**
   * Sets the filename for the detail view of this table.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @param detailViewRelativePath
   * @throws ServicesAvailabilityException 
   */
  public void setDetailViewFilename( CommonApplication ctxt, String appName, DbHandle db, String tableId, String detailViewRelativePath) throws ServicesAvailabilityException {

    // TODO: this should probably use the detailView name as the aspect
    KeyValueStoreEntry e = KeyValueStoreUtils.buildEntry(tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            LocalKeyValueStoreConstants.Tables.KEY_DETAIL_VIEW_FILE_NAME,
            ElementDataType.configpath, detailViewRelativePath);
    ctxt.getDatabase().replaceTableMetadata(appName, db, e);
  }

  /**
   * Wrapper to handle database interactions to setDetailViewFilename
   *
   * @param ctxt
   * @param appName
   * @param tableId
   * @param detailViewRelativePath
   * @throws ServicesAvailabilityException
   */
  public void atomicSetDetailViewFilename( CommonApplication ctxt, String appName, String tableId, String detailViewRelativePath) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      setDetailViewFilename(ctxt, appName, db, tableId, detailViewRelativePath);
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  /**
   * Get the filename for the detail view of this table.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return null if none defined.
   * @throws ServicesAvailabilityException 
   */
  public String getListViewFilename( CommonApplication ctxt, String appName, DbHandle db, String tableId) throws ServicesAvailabilityException {

    // TODO: this should probably use the listView name as the aspect
    List<KeyValueStoreEntry> kvsList = ctxt.getDatabase()
        .getTableMetadata(appName, db, tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            LocalKeyValueStoreConstants.Tables.KEY_LIST_VIEW_FILE_NAME, null).getEntries();
    if (kvsList.size() != 1) {
      return null;
    }
    String rawValue = KeyValueStoreUtils.getString(appName, kvsList.get(0));
    return rawValue;
  }

  /**
   * Sets the filename for the detail view of this table.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @param listViewRelativePath
   * @throws ServicesAvailabilityException 
   */
  public void setListViewFilename( CommonApplication ctxt, String appName, DbHandle db, String tableId, String listViewRelativePath) throws ServicesAvailabilityException {

    // TODO: this should probably use the listView name as the aspect
    KeyValueStoreEntry e = KeyValueStoreUtils.buildEntry(tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            LocalKeyValueStoreConstants.Tables.KEY_LIST_VIEW_FILE_NAME,
            ElementDataType.configpath, listViewRelativePath);
    ctxt.getDatabase().replaceTableMetadata(appName, db, e);
  }

  /**
   * Wrapper to handle database interactions to setListViewFilename
   *
   * @param ctxt
   * @param appName
   * @param tableId
   * @param listViewRelativePath
   * @throws ServicesAvailabilityException
   */
  public void atomicSetListViewFilename( CommonApplication ctxt, String appName, String tableId, String listViewRelativePath) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      setListViewFilename(ctxt, appName, db, tableId, listViewRelativePath);
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  /**
   * Get the filename for the detail view of this table.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return null if none defined.
   * @throws ServicesAvailabilityException 
   */
  public String getMapListViewFilename( CommonApplication ctxt, String appName, DbHandle db, String tableId) throws ServicesAvailabilityException {
    // TODO: this should probably use a mapView name as the aspect
    List<KeyValueStoreEntry> kvsList = ctxt.getDatabase()
        .getTableMetadata(appName, db, tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            LocalKeyValueStoreConstants.Tables.KEY_MAP_LIST_VIEW_FILE_NAME, null).getEntries();
    if (kvsList.size() != 1) {
      return null;
    }
    String rawValue = KeyValueStoreUtils.getString(appName, kvsList.get(0));
    return rawValue;
  }

  /**
   * Sets the filename for the detail view of this table.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @param mapListViewRelativePath
   * @throws ServicesAvailabilityException 
   */
  public void setMapListViewFilename( CommonApplication ctxt, String appName, DbHandle db, String tableId, String mapListViewRelativePath) throws ServicesAvailabilityException {
    // TODO: this should probably use a mapView name as the aspect
    KeyValueStoreEntry e = KeyValueStoreUtils.buildEntry(tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            LocalKeyValueStoreConstants.Tables.KEY_MAP_LIST_VIEW_FILE_NAME,
            ElementDataType.configpath, mapListViewRelativePath);
    ctxt.getDatabase().replaceTableMetadata(appName, db, e);
  }

  /**
   * Wrapper to handle the database interactions for setMapListViewFilename()
   *
   * @param ctxt
   * @param appName
   * @param tableId
   * @param mapListViewRelativePath
   * @throws ServicesAvailabilityException
   */
  public void atomicSetMapListViewFilename( CommonApplication ctxt, String appName, String tableId, String mapListViewRelativePath) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      setMapListViewFilename(ctxt, appName, db, tableId, mapListViewRelativePath);
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  public MapViewColorRuleInfo getMapListViewColorRuleInfo(CommonApplication ctxt, String appName,
      DbHandle db, String tableId) throws ServicesAvailabilityException {
    // TODO: this should probably use the mapView name as the aspect
    List<KeyValueStoreEntry> kvsList = ctxt.getDatabase()
        .getTableMetadata(appName, db, tableId, LocalKeyValueStoreConstants.Map.PARTITION,
            KeyValueStoreConstants.ASPECT_DEFAULT, null, null).getEntries();
    // Grab the key value store helper from the map fragment.
    String colorType = null;
    String colorColumnElementKey = null;
    for (KeyValueStoreEntry entry : kvsList) {
      if ( entry.key.equals(LocalKeyValueStoreConstants.Map.KEY_COLOR_RULE_TYPE) ) {
        colorType = KeyValueStoreUtils.getString(appName, entry);
      } else if ( entry.key.equals(LocalKeyValueStoreConstants.Map.KEY_COLOR_RULE_COLUMN) ) {
        colorColumnElementKey = KeyValueStoreUtils.getString(appName, entry);
      }
    }

    if (colorType != null && !colorType.equals(LocalKeyValueStoreConstants.Map.COLOR_TYPE_TABLE) &&
            !colorType.equals(LocalKeyValueStoreConstants.Map.COLOR_TYPE_STATUS)) {
      colorType = LocalKeyValueStoreConstants.Map.COLOR_TYPE_NONE;
    }

    MapViewColorRuleInfo info = new MapViewColorRuleInfo(colorType, colorColumnElementKey);
    return info;
  }


  public void setMapListViewColorRuleInfo( CommonApplication ctxt, String appName, DbHandle db, String tableId, MapViewColorRuleInfo info) throws ServicesAvailabilityException {
    // TODO: this should probably use the mapView name as the aspect
    KeyValueStoreEntry entryColorElementKey = KeyValueStoreUtils.buildEntry(tableId,
            LocalKeyValueStoreConstants.Map.PARTITION,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            LocalKeyValueStoreConstants.Map.KEY_COLOR_RULE_COLUMN,
            ElementDataType.string,
            null);
    KeyValueStoreEntry entryColorRuleType = KeyValueStoreUtils.buildEntry(tableId,
            LocalKeyValueStoreConstants.Map.PARTITION,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            LocalKeyValueStoreConstants.Map.KEY_COLOR_RULE_TYPE,
            ElementDataType.string, (info == null) ? null : info.colorType);

      ctxt.getDatabase().replaceTableMetadata(appName, db, entryColorElementKey);
      ctxt.getDatabase().replaceTableMetadata(appName, db, entryColorRuleType);
  }

  public String getMapListViewLatitudeElementKey( CommonApplication ctxt, String appName, DbHandle db, String tableId, OrderedColumns orderedDefns) throws ServicesAvailabilityException {
    // TODO: this should probably use a mapView name as the aspect
    List<KeyValueStoreEntry> kvsList = ctxt.getDatabase()
        .getTableMetadata(appName, db, tableId, LocalKeyValueStoreConstants.Map.PARTITION,
            KeyValueStoreConstants.ASPECT_DEFAULT, LocalKeyValueStoreConstants.Map.KEY_MAP_LAT_COL,
            null).getEntries();
    String rawValue = null;
    if ( kvsList.size() == 1 ) {
      rawValue = KeyValueStoreUtils.getString(appName, kvsList.get(0));
    }
    if ( rawValue == null ) {
      // Go through each of the columns and check to see if there are
      // any columns labeled latitude or longitude.
      final List<ColumnDefinition> geoPointCols = orderedDefns.getGeopointColumnDefinitions();
      for (ColumnDefinition cd : orderedDefns.getColumnDefinitions()) {
        if (orderedDefns.isLatitudeColumnDefinition(geoPointCols, cd)) {
          rawValue = cd.getElementKey();
          break;
        }
      }

    }
    return rawValue;
  }

  public String getMapListViewLongitudeElementKey( CommonApplication ctxt, String appName, DbHandle db, String tableId, OrderedColumns orderedDefns) throws ServicesAvailabilityException {
    // TODO: this should probably use a mapView name as the aspect
    List<KeyValueStoreEntry> kvsList = ctxt.getDatabase()
        .getTableMetadata(appName, db, tableId, LocalKeyValueStoreConstants.Map.PARTITION,
            KeyValueStoreConstants.ASPECT_DEFAULT, LocalKeyValueStoreConstants.Map.KEY_MAP_LONG_COL,
            null).getEntries();
    String rawValue = null;
    if ( kvsList.size() == 1 ) {
      rawValue = KeyValueStoreUtils.getString(appName, kvsList.get(0));
    }
    if ( rawValue == null ) {
      // Go through each of the columns and check to see if there are
      // any columns labeled latitude or longitude.
      final List<ColumnDefinition> geoPointCols = orderedDefns.getGeopointColumnDefinitions();
      for (ColumnDefinition cd : orderedDefns.getColumnDefinitions()) {
        if (orderedDefns.isLongitudeColumnDefinition(geoPointCols, cd)) {
          rawValue = cd.getElementKey();
          break;
        }
      }

    }
    return rawValue;
  }

  /**
   * Get the elementKey of the sort-by column
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return null if none defined.
   * @throws ServicesAvailabilityException 
   */
  public String getSortColumn( CommonApplication ctxt, String appName, DbHandle db, String tableId) throws ServicesAvailabilityException {
    List<KeyValueStoreEntry> kvsList = ctxt.getDatabase()
        .getTableMetadata(appName, db, tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT, KeyValueStoreConstants.TABLE_SORT_COL, null)
        .getEntries();
    if ( kvsList.size() != 1 ) {
      return null;
    }
    String rawValue = KeyValueStoreUtils.getString(appName, kvsList.get(0));
    return rawValue;
  }

  /**
   * Sets the table's sort column.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @param elementKey
   * @throws ServicesAvailabilityException 
   */
  public void setSortColumn( CommonApplication ctxt, String appName, DbHandle db, String tableId, String elementKey) throws ServicesAvailabilityException {
    KeyValueStoreEntry e = KeyValueStoreUtils.buildEntry(tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            KeyValueStoreConstants.TABLE_SORT_COL,
            ElementDataType.string, elementKey);
    ctxt.getDatabase().replaceTableMetadata(appName, db, e);
  }

  /**
   * Wrapper to handle database interactions for setSortColumn()
   *
   * @param ctxt
   * @param appName
   * @param tableId
   * @param elementKey
   * @throws ServicesAvailabilityException
   */
  public void atomicSetSortColumn( CommonApplication ctxt, String appName, String tableId, String elementKey) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      setSortColumn(ctxt, appName, db, tableId, elementKey);
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  /**
   * Return the sort order of the display (ASC or DESC)
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return ASC if none specified.
   * @throws ServicesAvailabilityException 
   */
  public String getSortOrder( CommonApplication ctxt, String appName, DbHandle db, String tableId) throws ServicesAvailabilityException {
    List<KeyValueStoreEntry> kvsList = ctxt.getDatabase()
        .getTableMetadata(appName, db, tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT, KeyValueStoreConstants.TABLE_SORT_ORDER, null)
        .getEntries();
    if (kvsList.size() != 1) {
      return null;
    }
    String rawValue = KeyValueStoreUtils.getString(appName, kvsList.get(0));
    if ( rawValue == null ) {
      return DEFAULT_KEY_SORT_ORDER;
    }
    return rawValue;
  }

  /**
   * Set the sort order of the display (ASC or DESC)
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @param sortOrder
   * @throws ServicesAvailabilityException 
   */
  public void setSortOrder( CommonApplication ctxt, String appName, DbHandle db, String tableId, String sortOrder) throws ServicesAvailabilityException {
    KeyValueStoreEntry e = KeyValueStoreUtils.buildEntry(tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            KeyValueStoreConstants.TABLE_SORT_ORDER,
            ElementDataType.string, sortOrder);
    ctxt.getDatabase().replaceTableMetadata(appName, db, e);
  }

  /**
   * Wrapper to handle database interactions for setSortOrder()
   *
   * @param ctxt
   * @param appName
   * @param tableId
   * @param sortOrder
   * @throws ServicesAvailabilityException
   */
  public void atomicSetSortOrder( CommonApplication ctxt, String appName, String tableId, String sortOrder) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      setSortOrder(ctxt, appName, db, tableId, sortOrder);
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  /**
   * Return the element key of the indexed (frozen) column.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return null if none
   * @throws ServicesAvailabilityException 
   */
  public String getIndexColumn( CommonApplication ctxt, String appName, DbHandle db, String tableId) throws ServicesAvailabilityException {
    List<KeyValueStoreEntry> kvsList = ctxt.getDatabase()
        .getTableMetadata(appName, db, tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT, KeyValueStoreConstants.TABLE_INDEX_COL, null)
        .getEntries();
    if (kvsList.size() != 1) {
      return null;
    }
    String rawValue = KeyValueStoreUtils.getString(appName, kvsList.get(0));
    return rawValue;
  }

  /**
   * Set the elementKey of the indexed (frozen) column.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @param elementKey
   * @throws ServicesAvailabilityException 
   */
  public void setIndexColumn( CommonApplication ctxt, String appName, DbHandle db, String tableId, String elementKey) throws ServicesAvailabilityException {
    KeyValueStoreEntry e = KeyValueStoreUtils.buildEntry(tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            KeyValueStoreConstants.TABLE_INDEX_COL,
            ElementDataType.string, elementKey);
    ctxt.getDatabase().replaceTableMetadata(appName, db, e);
  }

  /**
   * Wrapper to handle database interactions for setIndexColumn()
   *
   * @param ctxt
   * @param appName
   * @param tableId
   * @param elementKey
   * @throws ServicesAvailabilityException
   */
  public void atomicSetIndexColumn( CommonApplication ctxt, String appName, String tableId, String elementKey) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      setIndexColumn(ctxt, appName, db, tableId, elementKey);
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  public int getSpreadsheetViewFontSize( CommonApplication ctxt, String appName, DbHandle db, String tableId) throws ServicesAvailabilityException {
    List<KeyValueStoreEntry> kvsList = ctxt.getDatabase()
        .getTableMetadata(appName, db, tableId, LocalKeyValueStoreConstants.Spreadsheet.PARTITION,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            LocalKeyValueStoreConstants.Spreadsheet.KEY_FONT_SIZE, null).getEntries();
    Integer fontSize = null;
    if ( kvsList.size() == 1 ) {
      fontSize = KeyValueStoreUtils.getInteger(appName, kvsList.get(0));
    }

    if ( fontSize == null ) {
      PropertiesSingleton props = CommonToolProperties.get(ctxt, appName);
      Integer fs = props.getIntegerProperty(CommonToolProperties.KEY_FONT_SIZE);
      fontSize = fs == null ? CommonToolProperties.DEFAULT_FONT_SIZE : fs.intValue();
    }
    return fontSize;
  }

  /**
   * Set the font size to be used on the spreadsheet view.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @param fontSize
   * @throws ServicesAvailabilityException
   */
  public void setSpreadsheetViewFontSize( CommonApplication ctxt, String appName, DbHandle db, String tableId, Integer fontSize) throws ServicesAvailabilityException {
    KeyValueStoreEntry e = KeyValueStoreUtils.buildEntry(tableId,
            LocalKeyValueStoreConstants.Spreadsheet.PARTITION,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            LocalKeyValueStoreConstants.Spreadsheet.KEY_FONT_SIZE,
            ElementDataType.integer, Integer.toString(fontSize));
    ctxt.getDatabase().replaceTableMetadata(appName, db, e);
  }

  /**
   * Wrapper to handle database interactions for setSpreadsheetViewFontSize()
   *
   * @param ctxt
   * @param appName
   * @param tableId
   * @param fontSize
   * @throws ServicesAvailabilityException
   */
  public void atomicSetSpreadsheetViewFontSize( CommonApplication ctxt, String appName, String tableId, Integer fontSize) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      setSpreadsheetViewFontSize(ctxt, appName, db, tableId, fontSize);
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  /**
   * Get the group-by columns, in order
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return empty list if none
   * @throws ServicesAvailabilityException 
   */
  public ArrayList<String> getGroupByColumns( CommonApplication ctxt, String appName, DbHandle db, String tableId) throws ServicesAvailabilityException {
    List<KeyValueStoreEntry> kvsList = ctxt.getDatabase()
        .getTableMetadata(appName, db, tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT, KeyValueStoreConstants.TABLE_GROUP_BY_COLS, null)
        .getEntries();
    if (kvsList.size() != 1) {
      return new ArrayList<String>();
    }
    ArrayList<String> rawValue = KeyValueStoreUtils.getArray(appName, kvsList.get(0), String.class);
    if ( rawValue == null ) {
      return new ArrayList<String>();
    }
    return rawValue;
  }

  /**
   * Set the group-by columns.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @param elementKeys
   * @throws ServicesAvailabilityException 
   */
  public void setGroupByColumns( CommonApplication ctxt, String appName, DbHandle db, String tableId, ArrayList<String> elementKeys) throws ServicesAvailabilityException {
    String list = null;
    try {
      list = ODKFileUtils.mapper.writeValueAsString(elementKeys);
    } catch (JsonProcessingException e1) {
      e1.printStackTrace();
      throw new IllegalArgumentException("Unexpected groupByCols conversion failure!");
    }
    KeyValueStoreEntry e = KeyValueStoreUtils.buildEntry(tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            KeyValueStoreConstants.TABLE_GROUP_BY_COLS,
            ElementDataType.array, list);
    ctxt.getDatabase().replaceTableMetadata(appName, db, e);
  }

  /**
   * Wrapper for handling database interactions for adding the indicate column to the
   * end of the groupBy columns array. If it is already in the array, it is removed and
   * appended to the end.
   *
   * @param ctxt
   * @param appName
   * @param tableId
   * @param elementKey
   * @throws ServicesAvailabilityException
   */
  public void atomicAddGroupByColumn( CommonApplication ctxt, String appName, String tableId, String elementKey) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      ArrayList<String> elementKeys = getGroupByColumns(ctxt, appName, db, tableId);
      elementKeys.remove(elementKey);
      elementKeys.add(elementKey);
      setGroupByColumns(ctxt, appName, db, tableId, elementKeys);
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  /**
   * Wrapper for handling database interactions for removing the indicated column from
   * the groupBy columns list.
   *
   * @param ctxt
   * @param appName
   * @param tableId
   * @param elementKey
   * @throws ServicesAvailabilityException
   */
  public void atomicRemoveGroupByColumn( CommonApplication ctxt, String appName, String tableId, String elementKey) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      ArrayList<String> elementKeys = getGroupByColumns(ctxt, appName, db, tableId);
      elementKeys.remove(elementKey);
      setGroupByColumns(ctxt, appName, db, tableId, elementKeys);
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  /**
   * Get the order of display of the columns in the spreadsheet view
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return empty list of none specified. Otherwise the elementKeys in the order of display.
   * @throws ServicesAvailabilityException 
   */
  public ArrayList<String> getColumnOrder( CommonApplication ctxt, String appName, DbHandle db, String tableId, OrderedColumns columns) throws ServicesAvailabilityException {
    List<KeyValueStoreEntry> kvsList = ctxt.getDatabase()
        .getTableMetadata(appName, db, tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT, KeyValueStoreConstants.TABLE_COL_ORDER, null)
        .getEntries();
    ArrayList<String> rawValue = null;
    if ( kvsList.size() == 1 ) {
      rawValue = KeyValueStoreUtils.getArray(appName, kvsList.get(0), String.class);
    }
    if ( rawValue == null || rawValue.size() == 0 ) {
      // return the list of persisted columns
      rawValue = new ArrayList<String>(columns.getRetentionColumnNames());
    }
    return rawValue;
  }

  /**
   * Set the order of display of the columns in the spreadsheet view.
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @param elementKeys
   * @throws ServicesAvailabilityException 
   */
  public void setColumnOrder( CommonApplication ctxt, String appName, DbHandle db, String tableId, ArrayList<String> elementKeys) throws ServicesAvailabilityException {
    String list = null;
    try {
      list = ODKFileUtils.mapper.writeValueAsString(elementKeys);
    } catch (JsonProcessingException e1) {
      e1.printStackTrace();
      throw new IllegalArgumentException("Unexpected columnOrder conversion failure!");
    }
    KeyValueStoreEntry e = KeyValueStoreUtils.buildEntry(tableId, KeyValueStoreConstants.PARTITION_TABLE,
            KeyValueStoreConstants.ASPECT_DEFAULT,
            KeyValueStoreConstants.TABLE_COL_ORDER,
            ElementDataType.array, list);
    ctxt.getDatabase().replaceTableMetadata(appName, db, e);
  }

  public void atomicSetColumnOrder( CommonApplication ctxt, String appName, String tableId, ArrayList<String> elementKeys) throws ServicesAvailabilityException {
    DbHandle db = null;
    try {
      db = ctxt.getDatabase().openDatabase(appName);

      setColumnOrder(ctxt, appName, db, tableId, elementKeys);
    } catch (ServicesAvailabilityException e) {
      WebLogger.getLogger(appName).printStackTrace(e);
      throw e;
    } finally {
      if ( db != null ) {
        try {
          ctxt.getDatabase().closeDatabase(appName, db);
        } catch (ServicesAvailabilityException e) {
          WebLogger.getLogger(appName).printStackTrace(e);
          throw e;
        }
      }
    }
  }

  /**
   *
   * @param ctxt
   * @param appName
   * @param db
   * @param tableId
   * @return
   * @throws ServicesAvailabilityException
   */
  public TableColumns getTableColumns( CommonApplication ctxt, String appName, DbHandle db, String tableId ) throws ServicesAvailabilityException {
    String[] adminColumns = ctxt.getDatabase().getAdminColumns();
    HashMap<String,String> colDisplayNames = new HashMap<String,String>();
    OrderedColumns orderedDefns = ctxt.getDatabase()
        .getUserDefinedColumns(appName, db, tableId);
    for (ColumnDefinition cd : orderedDefns.getColumnDefinitions()) {
      if (cd.isUnitOfRetention()) {
        String localizedDisplayName;
        localizedDisplayName = ColumnUtil.get().getLocalizedDisplayName(ctxt,
            appName, db, tableId, cd.getElementKey());

        colDisplayNames.put(cd.getElementKey(), localizedDisplayName);
      }
    }
    return new TableColumns(orderedDefns, adminColumns, colDisplayNames);
  }
}
