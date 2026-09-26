;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

;; After a change, run bb kondo:fndefs and bb squint. A new or removed fn also
;; needs bb build:wasm, because the wasm exports each key.
#?(:clj
   (ns net.willcohen.gdal.fndefs)
   :cljs
   (ns fndefs))

(def ^{:tag 'long} GDAL_OF_READONLY 0x00)
(def ^{:tag 'long} GDAL_OF_UPDATE   0x01)
(def ^{:tag 'long} GDAL_OF_ALL      0x00)
(def ^{:tag 'long} GDAL_OF_RASTER   0x02)
(def ^{:tag 'long} GDAL_OF_VECTOR   0x04)
(def ^{:tag 'long} GDAL_OF_GNM      0x08)
(def ^{:tag 'long} GDAL_OF_MULTIDIM_RASTER 0x10)

(def ^{:tag 'long} wkbXDR 0) ; big-endian
(def ^{:tag 'long} wkbNDR 1) ; little-endian

(def ^{:tag 'long} wkbUnknown         0)
(def ^{:tag 'long} wkbPoint           1)
(def ^{:tag 'long} wkbLineString      2)
(def ^{:tag 'long} wkbPolygon         3)
(def ^{:tag 'long} wkbMultiPoint      4)
(def ^{:tag 'long} wkbMultiLineString 5)
(def ^{:tag 'long} wkbMultiPolygon    6)
(def ^{:tag 'long} wkbGeometryCollection 7)

(def ^{:tag 'long} OFTInteger      0)
(def ^{:tag 'long} OFTIntegerList  1)
(def ^{:tag 'long} OFTReal         2)
(def ^{:tag 'long} OFTRealList     3)
(def ^{:tag 'long} OFTString       4)
(def ^{:tag 'long} OFTStringList   5)
(def ^{:tag 'long} OFTBinary       8)
(def ^{:tag 'long} OFTDate         9)
(def ^{:tag 'long} OFTTime        10)
(def ^{:tag 'long} OFTDateTime    11)
(def ^{:tag 'long} OFTInteger64   12)
(def ^{:tag 'long} OFTInteger64List 13)

(def ^{:tag 'long} OFSTNone    0)
(def ^{:tag 'long} OFSTBoolean 1)

(def ^{:tag 'long} GDT_Unknown   0)
(def ^{:tag 'long} GDT_Byte      1)
(def ^{:tag 'long} GDT_UInt16    2)
(def ^{:tag 'long} GDT_Int16     3)
(def ^{:tag 'long} GDT_UInt32    4)
(def ^{:tag 'long} GDT_Int32     5)
(def ^{:tag 'long} GDT_Float32   6)
(def ^{:tag 'long} GDT_Float64   7)
(def ^{:tag 'long} GDT_CInt16    8)
(def ^{:tag 'long} GDT_CInt32    9)
(def ^{:tag 'long} GDT_CFloat32 10)
(def ^{:tag 'long} GDT_CFloat64 11)
(def ^{:tag 'long} GDT_UInt64   12)
(def ^{:tag 'long} GDT_Int64    13)
(def ^{:tag 'long} GDT_Int8     14)
(def ^{:tag 'long} GDT_Float16  15)
(def ^{:tag 'long} GDT_CFloat16 16)

(def ^{:tag 'long} CE_None    0)
(def ^{:tag 'long} CE_Debug   1)
(def ^{:tag 'long} CE_Warning 2)
(def ^{:tag 'long} CE_Failure 3)
(def ^{:tag 'long} CE_Fatal   4)

(def ^{:tag 'long} GA_ReadOnly 0)
(def ^{:tag 'long} GA_Update   1)

(def ^{:tag 'long} GF_Read  0)
(def ^{:tag 'long} GF_Write 1)

(def ^{:tag 'long} OGRERR_NONE                      0)
(def ^{:tag 'long} OGRERR_NOT_ENOUGH_DATA           1)
(def ^{:tag 'long} OGRERR_NOT_ENOUGH_MEMORY         2)
(def ^{:tag 'long} OGRERR_UNSUPPORTED_GEOMETRY_TYPE 3)
(def ^{:tag 'long} OGRERR_UNSUPPORTED_OPERATION     4)
(def ^{:tag 'long} OGRERR_CORRUPT_DATA              5)
(def ^{:tag 'long} OGRERR_FAILURE                   6)
(def ^{:tag 'long} OGRERR_UNSUPPORTED_SRS           7)
(def ^{:tag 'long} OGRERR_INVALID_HANDLE            8)
(def ^{:tag 'long} OGRERR_NON_EXISTING_FEATURE      9)

(def ^{:tag 'long} OAMS_TRADITIONAL_GIS_ORDER 0)
(def ^{:tag 'long} OAMS_AUTHORITY_COMPLIANT   1)
(def ^{:tag 'long} OAMS_CUSTOM                2)

(def ^{:tag 'long} GRA_NearestNeighbour 0)
(def ^{:tag 'long} GRA_Bilinear         1)
(def ^{:tag 'long} GRA_Cubic            2)
(def ^{:tag 'long} GRA_CubicSpline      3)
(def ^{:tag 'long} GRA_Lanczos          4)
(def ^{:tag 'long} GRA_Average          5)
(def ^{:tag 'long} GRA_Mode             6)

(def fndefs
  {:GDALAllRegister        {:rettype :void
                            :argtypes []}
   :GDALGetDriverByName    {:rettype :pointer
                            :argtypes [[:name :string]]}
   :GDALGetDriverCount     {:rettype :int32
                            :argtypes []}

   :GDALOpen               {:rettype :pointer
                            :argtypes [[:filename :string]
                                       [:access :int32]]}
   :GDALOpenEx             {:rettype :pointer
                            :argtypes [[:filename :string]
                                       [:open-flags :int32]
                                       [:allowed-drivers :pointer?]  ; char **
                                       [:open-options :pointer?]     ; char **
                                       [:sibling-files :pointer?]]}   ; char **
   :GDALClose              {:rettype :int32  ; CPLErr, since GDAL 3.7
                            :argtypes [[:dataset :pointer?]]}
   :GDALCreateCopy         {:rettype :pointer
                            :argtypes [[:driver :pointer]
                                       [:filename :string]
                                       [:src-ds :pointer]
                                       [:strict :int32]
                                       [:options :pointer?]        ; char**
                                       [:progress-fn :pointer?]
                                       [:progress-arg :pointer?]]}
   :GDALVersionInfo        {:rettype :string
                            :argtypes [[:request :string]]}

   :CPLErrorReset          {:rettype :void
                            :argtypes []}
   :CPLGetLastErrorNo      {:rettype :int32  ; CPLErrorNum
                            :argtypes []}
   :CPLGetLastErrorMsg     {:rettype :string
                            :argtypes []}
   ;; Not CPLError, because dt-ffi and the emscripten ccall cannot call a
   ;; variadic fn.
   :CPLErrorSetState       {:rettype :void
                            :argtypes [[:err-class :int32]  ; CPLErr
                                       [:err-no :int32]     ; CPLErrorNum
                                       [:msg :string]]}

   :CPLGetConfigOption     {:rettype :string?
                            :argtypes [[:key :string]
                                       [:default :string?]]}
   ;; A nil value removes the option.
   :CPLSetConfigOption     {:rettype :void
                            :argtypes [[:key :string]
                                       [:value :string?]]}

   :GDALGetRasterXSize     {:rettype :int32
                            :argtypes [[:dataset :pointer]]}
   :GDALGetRasterYSize     {:rettype :int32
                            :argtypes [[:dataset :pointer]]}
   :GDALGetRasterCount     {:rettype :int32
                            :argtypes [[:dataset :pointer]]}

   :GDALGetRasterBand      {:rettype :pointer
                            :argtypes [[:dataset :pointer]
                                       [:band-num :int32]]}
   :GDALGetRasterDataType  {:rettype :int32
                            :argtypes [[:band :pointer]]}
   :GDALGetRasterBandXSize {:rettype :int32
                            :argtypes [[:band :pointer]]}
   :GDALGetRasterBandYSize {:rettype :int32
                            :argtypes [[:band :pointer]]}
   :GDALRasterIO           {:rettype :int32  ; CPLErr
                            :argtypes [[:band :pointer]
                                       [:rw-flag :int32]
                                       [:xoff :int32]
                                       [:yoff :int32]
                                       [:xsize :int32]
                                       [:ysize :int32]
                                       [:buffer :pointer]
                                       [:buf-xsize :int32]
                                       [:buf-ysize :int32]
                                       [:buf-type :int32]
                                       [:pixel-space :int32]
                                       [:line-space :int32]]}

   :GDALDatasetGetLayerCount {:rettype :int32
                              :argtypes [[:dataset :pointer]]}
   :GDALDatasetGetLayer    {:rettype :pointer
                            :argtypes [[:dataset :pointer]
                                       [:idx :int32]]}
   :OGR_L_GetName          {:rettype :string
                            :argtypes [[:layer :pointer]]}
   :OGR_L_ResetReading     {:rettype :void
                            :argtypes [[:layer :pointer]]}
   :OGR_L_GetNextFeature   {:rettype :pointer
                            :argtypes [[:layer :pointer]]}
   :OGR_L_GetFeatureCount  {:rettype :int64
                            :argtypes [[:layer :pointer]
                                       [:force :int32]]}
   :OGR_L_GetExtent        {:rettype :int32  ; OGRErr
                            :argtypes [[:layer :pointer]
                                       [:envelope-out :pointer]
                                       [:force :int32]]}

   :OGR_F_GetGeometryRef   {:rettype :pointer
                            :argtypes [[:feature :pointer]]}
   :OGR_F_GetFID           {:rettype :int64
                            :argtypes [[:feature :pointer]]}
   :OGR_F_GetFieldAsString {:rettype :string  ; borrowed const char*
                            :argtypes [[:feature :pointer]
                                       [:idx :int32]]}
   :OGR_F_GetFieldAsInteger {:rettype :int32
                             :argtypes [[:feature :pointer]
                                        [:idx :int32]]}
   :OGR_F_GetFieldAsInteger64 {:rettype :int64
                               :argtypes [[:feature :pointer]
                                          [:idx :int32]]}
   :OGR_F_GetFieldAsDouble {:rettype :float64
                            :argtypes [[:feature :pointer]
                                       [:idx :int32]]}
   :OGR_F_GetFieldDefnRef  {:rettype :pointer  ; borrowed OGRFieldDefnH
                            :argtypes [[:feature :pointer]
                                       [:idx :int32]]}
   :OGR_F_GetFieldIndex    {:rettype :int32  ; -1 for an unknown name
                            :argtypes [[:feature :pointer]
                                       [:name :string]]}
   :OGR_F_IsFieldNull      {:rettype :int32
                            :argtypes [[:feature :pointer]
                                       [:idx :int32]]}
   :OGR_F_IsFieldSetAndNotNull {:rettype :int32
                                :argtypes [[:feature :pointer]
                                           [:idx :int32]]}
   :OGR_L_GetSpatialRef    {:rettype :pointer  ; borrowed OGRSpatialReferenceH
                            :argtypes [[:layer :pointer]]}

   :OGR_L_GetLayerDefn     {:rettype :pointer  ; borrowed OGRFeatureDefnH
                            :argtypes [[:layer :pointer]]}
   :OGR_FD_GetFieldCount   {:rettype :int32
                            :argtypes [[:featuredefn :pointer]]}
   :OGR_FD_GetFieldDefn    {:rettype :pointer  ; borrowed OGRFieldDefnH
                            :argtypes [[:featuredefn :pointer]
                                       [:idx :int32]]}
   :OGR_Fld_GetNameRef     {:rettype :string  ; borrowed const char*
                            :argtypes [[:fielddefn :pointer]]}
   :OGR_Fld_GetType        {:rettype :int32  ; OGRFieldType
                            :argtypes [[:fielddefn :pointer]]}
   :OGR_G_WkbSize          {:rettype :int32
                            :argtypes [[:geometry :pointer]]}
   :OGR_G_ExportToWkb      {:rettype :int32  ; OGRErr
                            :argtypes [[:geometry :pointer]
                                       [:byte-order :int32]
                                       [:wkb-out :pointer]]}  ; OGR_G_WkbSize bytes
   :OGR_G_ExportToIsoWkb   {:rettype :int32  ; OGRErr
                            :argtypes [[:geometry :pointer]
                                       [:byte-order :int32]
                                       [:wkb-out :pointer]]}
   :OGR_F_Destroy          {:rettype :void
                            :argtypes [[:feature :pointer?]]}

   :OSRNewSpatialReference        {:rettype :pointer
                                   :argtypes [[:wkt :string?]]} ; nil or "" gives an empty SRS
   :OSRDestroySpatialReference    {:rettype :void
                                   :argtypes [[:srs :pointer?]]}
   :OSRSetFromUserInput           {:rettype :int32  ; OGRErr
                                   :argtypes [[:srs :pointer]
                                              [:user-input :string]]}
   :OSRImportFromEPSG             {:rettype :int32  ; OGRErr
                                   :argtypes [[:srs :pointer]
                                              [:epsg :int32]]}
   :OSRSetAxisMappingStrategy     {:rettype :void
                                   :argtypes [[:srs :pointer]
                                              [:strategy :int32]]}
   :OSRGetAuthorityCode           {:rettype :string?  ; NULL for no authority
                                   :argtypes [[:srs :pointer]
                                              [:target-key :string?]]}
   :OSRGetAuthorityName           {:rettype :string?
                                   :argtypes [[:srs :pointer]
                                              [:target-key :string?]]}

   :OCTNewCoordinateTransformation     {:rettype :pointer
                                        :argtypes [[:source-srs :pointer]
                                                   [:target-srs :pointer]]}
   :OCTDestroyCoordinateTransformation {:rettype :void
                                        :argtypes [[:oct :pointer?]]}
   :OCTTransform                       {:rettype :int32  ; 1 for success, the opposite of OGRErr
                                        :argtypes [[:oct :pointer]
                                                   [:point-count :int32]
                                                   [:xs :pointer]   ; double*
                                                   [:ys :pointer]   ; double*
                                                   [:zs :pointer?]]} ; double*

   :GDALGetGeoTransform   {:rettype :int32  ; CPLErr
                           :argtypes [[:dataset :pointer]
                                      [:gt-out :pointer]]} ; double[6] out
   :GDALGetProjectionRef  {:rettype :string  ; borrowed const char*
                           :argtypes [[:dataset :pointer]]}
   :GDALSetGeoTransform   {:rettype :int32  ; CPLErr
                           :argtypes [[:dataset :pointer]
                                      [:gt :pointer]]} ; double[6]
   :GDALSetProjection     {:rettype :int32  ; CPLErr
                           :argtypes [[:dataset :pointer]
                                      [:wkt :string]]}

   :GDALVectorTranslate {:rettype :pointer
                         :argtypes [[:dst-filename :string?]
                                    [:dst-ds :pointer?]
                                    [:src-count :int32]
                                    [:src-datasets :pointer]   ; GDALDatasetH*
                                    [:options :pointer?]       ; GDALVectorTranslateOptions*
                                    [:usage-error :pointer?]]}  ; int*
   :GDALVectorTranslateOptionsNew  {:rettype :pointer
                                    :argtypes [[:argv :pointer?]     ; char** NULL-terminated
                                               [:for-binary :pointer?]]}
   :GDALVectorTranslateOptionsFree {:rettype :void
                                    :argtypes [[:options :pointer?]]}

   :GDALWarp {:rettype :pointer
              :argtypes [[:dst-filename :string?]
                         [:dst-ds :pointer?]
                         [:src-count :int32]
                         [:src-datasets :pointer]   ; GDALDatasetH*
                         [:options :pointer?]       ; GDALWarpAppOptions*
                         [:usage-error :pointer?]]}  ; int*
   :GDALWarpAppOptionsNew  {:rettype :pointer
                            :argtypes [[:argv :pointer?]     ; char** NULL-terminated
                                       [:for-binary :pointer?]]}
   :GDALWarpAppOptionsFree {:rettype :void
                            :argtypes [[:options :pointer?]]}

   ;; Free the string of each char** out with VSIFree, because CPLFree is a C
   ;; macro and has no symbol.
   :OSRExportToWkt {:rettype :int32  ; OGRErr
                    :argtypes [[:srs :pointer]
                               [:wkt-out :pointer]]}  ; char** out

   :OSRExportToPROJJSON {:rettype :int32  ; OGRErr
                         :argtypes [[:srs :pointer]
                                    [:projjson-out :pointer]  ; char** out
                                    [:options :pointer?]]}    ; papszOptions

   :VSIFree {:rettype :void
             :argtypes [[:ptr :pointer?]]}

   ;; :jvm-only? leaves a fn out of gdal.mjs. JS cannot fill the worker heap
   ;; buffer of VSIMalloc or CPLMalloc.
   :VSIMalloc {:rettype :pointer  ; NULL on failure
               :jvm-only? true
               :argtypes [[:size :size-t]]}
   :VSIFileFromMemBuffer {:rettype :pointer  ; VSILFILE*, close with VSIFCloseL
                          :argtypes [[:filename :string]
                                     [:data :pointer?]      ; GByte*
                                     [:length :int64]       ; vsi_l_offset
                                     [:take-ownership :int32]]}
   :VSIFCloseL {:rettype :int32  ; 0 on success
                :argtypes [[:file :pointer]]}
   :VSIUnlink {:rettype :int32  ; 0 on success, -1 when there is no file
               :argtypes [[:filename :string]]}

   :CPLMalloc {:rettype :pointer
               :jvm-only? true
               :argtypes [[:size :size-t]]}

   ;; JS cannot make a C fn pointer, and null removes the HTTP stub of the
   ;; worker, which gdal.mjs cannot install again.
   :CPLHTTPSetFetchCallback {:rettype :void
                             :jvm-only? true
                             :argtypes [[:cbk :pointer?]    ; nil removes it, and GDAL then has no HTTP
                                        [:user :pointer?]]}

   :CSLAddString {:rettype :pointer       ; char**, free with CSLDestroy
                  :argtypes [[:list :pointer?]
                             [:string :string]]}
   :CSLDestroy   {:rettype :void
                  :argtypes [[:list :pointer?]]}

   :GDALCreate {:rettype :pointer
                :argtypes [[:driver :pointer]
                           [:filename :string]
                           [:xsize :int32]
                           [:ysize :int32]
                           [:bands :int32]
                           [:data-type :int32]
                           [:options :pointer?]]}   ; char**
   :GDALDatasetCreateLayer {:rettype :pointer      ; borrowed OGRLayerH
                            :argtypes [[:dataset :pointer]
                                       [:name :string]
                                       [:srs :pointer?]
                                       [:geom-type :int32]  ; wkb* geometry type
                                       [:options :pointer?]]} ; char**
   :OGR_Fld_Create  {:rettype :pointer             ; free with OGR_Fld_Destroy
                     :argtypes [[:name :string]
                                [:field-type :int32]]}
   :OGR_Fld_SetSubType {:rettype :void
                        :argtypes [[:fielddefn :pointer]
                                   [:subtype :int32]]}
   :OGR_Fld_Destroy {:rettype :void
                     :argtypes [[:fielddefn :pointer?]]}
   :OGR_L_CreateField {:rettype :int32  ; OGRErr
                       :argtypes [[:layer :pointer]
                                  [:fielddefn :pointer]
                                  [:approx-ok :int32]]}
   :OGR_F_Create {:rettype :pointer                ; free with OGR_F_Destroy
                  :argtypes [[:featuredefn :pointer]]}
   :OGR_F_SetFID {:rettype :int32  ; OGRErr
                  :argtypes [[:feature :pointer]
                             [:fid :int64]]}
   :OGR_F_SetFieldString {:rettype :void
                          :argtypes [[:feature :pointer]
                                     [:idx :int32]
                                     [:value :string]]}
   :OGR_F_SetFieldInteger {:rettype :void
                           :argtypes [[:feature :pointer]
                                      [:idx :int32]
                                      [:value :int32]]}
   :OGR_F_SetFieldInteger64 {:rettype :void
                             :argtypes [[:feature :pointer]
                                        [:idx :int32]
                                        [:value :int64]]}
   :OGR_F_SetFieldDouble {:rettype :void
                          :argtypes [[:feature :pointer]
                                     [:idx :int32]
                                     [:value :float64]]}
   :OGR_G_CreateFromWkb {:rettype :int32  ; OGRErr
                         :argtypes [[:wkb :pointer]
                                    [:srs :pointer?]
                                    [:geom-out :pointer]   ; OGRGeometryH* out
                                    [:n-bytes :int32]]}
   :OGR_F_SetGeometryDirectly {:rettype :int32  ; OGRErr
                               :argtypes [[:feature :pointer]
                                          [:geometry :pointer?]]}  ; nil clears
   :OGR_G_DestroyGeometry {:rettype :void
                           :argtypes [[:geometry :pointer?]]}
   :OGR_L_CreateFeature {:rettype :int32  ; OGRErr
                         :argtypes [[:layer :pointer]
                                    [:feature :pointer]]}
   :GDALDatasetStartTransaction  {:rettype :int32  ; OGRErr
                                  :argtypes [[:dataset :pointer]
                                             [:force :int32]]}
   :GDALDatasetCommitTransaction {:rettype :int32  ; OGRErr
                                  :argtypes [[:dataset :pointer]]}

   :OGR_L_SetAttributeFilter {:rettype :int32  ; OGRErr
                              :argtypes [[:layer :pointer]
                                         [:query :string?]]}  ; nil clears
   :OGR_L_SetSpatialFilterRect {:rettype :void
                                :argtypes [[:layer :pointer]
                                           [:min-x :float64]
                                           [:min-y :float64]
                                           [:max-x :float64]
                                           [:max-y :float64]]}
   :OGR_L_SetSpatialFilter {:rettype :void
                            :argtypes [[:layer :pointer]
                                       [:geometry :pointer?]]}  ; copied, nil clears
   :GDALDatasetExecuteSQL {:rettype :pointer  ; free with GDALDatasetReleaseResultSet
                           :argtypes [[:dataset :pointer]
                                      [:statement :string]
                                      [:spatial-filter :pointer?]
                                      [:dialect :string?]]}  ; nil, "OGRSQL" or "SQLITE"
   :GDALDatasetReleaseResultSet {:rettype :void
                                 :argtypes [[:dataset :pointer]
                                            [:layer :pointer?]]}
   :GDALDatasetGetLayerByName {:rettype :pointer  ; borrowed OGRLayerH
                               :argtypes [[:dataset :pointer]
                                          [:name :string]]}
   :OGR_L_GetFeature {:rettype :pointer  ; free with OGR_F_Destroy
                      :argtypes [[:layer :pointer]
                                 [:fid :int64]]}
   :OGR_L_GetGeomType {:rettype :int32  ; OGRwkbGeometryType
                       :argtypes [[:layer :pointer]]}
   :OGR_L_GetFIDColumn {:rettype :string  ; "" when the layer has no FID column
                        :argtypes [[:layer :pointer]]}
   :OGR_FD_GetGeomType {:rettype :int32  ; OGRwkbGeometryType
                        :argtypes [[:featuredefn :pointer]]}
   :OGR_L_TestCapability {:rettype :int32
                          :argtypes [[:layer :pointer]
                                     [:capability :string]]}
   :OGR_G_GetGeometryType {:rettype :int32  ; OGRwkbGeometryType
                           :argtypes [[:geometry :pointer]]}
   :OGR_G_ExportToWkt {:rettype :int32  ; OGRErr
                       :argtypes [[:geometry :pointer]
                                  [:wkt-out :pointer]]}  ; char** out
   ;; With :read-result, gdal-call on the JVM and the worker in JS read the
   ;; result, and free it when the caller owns it (readResult in
   ;; gdal-handler-overrides.mjs).
   :OGR_G_ExportToJson {:rettype :pointer  ; char*, NULL for a failure
                        :read-result :owned-string
                        :argtypes [[:geometry :pointer]]}
   :OGR_G_CreateFromWkt {:rettype :int32  ; OGRErr
                         :argtypes [[:wkt-in-out :pointer]  ; char**
                                    [:srs :pointer?]
                                    [:geom-out :pointer]]}  ; OGRGeometryH* out
   :OGR_G_CreateGeometryFromJson {:rettype :pointer  ; NULL for bad JSON
                                  :argtypes [[:json :string]]}

   :GDALGetRasterNoDataValue {:rettype :float64
                              :argtypes [[:band :pointer]
                                         [:success-out :pointer?]]}  ; int*, 1 for nodata
   :GDALSetRasterNoDataValue {:rettype :int32  ; CPLErr
                              :argtypes [[:band :pointer]
                                         [:nodata :float64]]}
   :GDALBuildOverviews {:rettype :int32  ; CPLErr
                        :argtypes [[:dataset :pointer]
                                   [:resampling :string]
                                   [:overview-count :int32]
                                   [:overview-list :pointer]  ; int*
                                   [:band-count :int32]
                                   [:band-list :pointer?]     ; int*
                                   [:progress-fn :pointer?]
                                   [:progress-arg :pointer?]]}
   :GDALGetOverviewCount {:rettype :int32
                          :argtypes [[:band :pointer]]}
   :GDALGetOverview {:rettype :pointer  ; borrowed GDALRasterBandH
                     :argtypes [[:band :pointer]
                                [:index :int32]]}
   :GDALGetMetadata {:rettype :pointer  ; char**
                     :read-result :string-list
                     :argtypes [[:object :pointer]
                                [:domain :string?]]}
   :GDALGetMetadataItem {:rettype :string?
                         :argtypes [[:object :pointer]
                                    [:name :string]
                                    [:domain :string?]]}
   :GDALGetSpatialRef {:rettype :pointer  ; borrowed OGRSpatialReferenceH
                       :argtypes [[:dataset :pointer]]}
   :GDALGetDataTypeName {:rettype :string?  ; for example "Byte"
                         :argtypes [[:data-type :int32]]}
   :GDALGetBlockSize {:rettype :void
                      :argtypes [[:band :pointer]
                                 [:x-size-out :pointer]   ; int*
                                 [:y-size-out :pointer]]} ; int*
   :GDALTranslate {:rettype :pointer
                   :argtypes [[:dst-filename :string]
                              [:src-dataset :pointer]
                              [:options :pointer?]       ; GDALTranslateOptions*
                              [:usage-error :pointer?]]} ; int*
   :GDALTranslateOptionsNew  {:rettype :pointer
                              :argtypes [[:argv :pointer?]
                                         [:for-binary :pointer?]]}
   :GDALTranslateOptionsFree {:rettype :void
                              :argtypes [[:options :pointer?]]}
   :GDALInfo {:rettype :pointer  ; char*
              :read-result :owned-string
              :argtypes [[:dataset :pointer]
                         [:options :pointer?]]}  ; GDALInfoOptions*
   :GDALInfoOptionsNew  {:rettype :pointer
                         :argtypes [[:argv :pointer?]
                                    [:for-binary :pointer?]]}
   :GDALInfoOptionsFree {:rettype :void
                         :argtypes [[:options :pointer?]]}
   :GDALVectorInfo {:rettype :pointer  ; char*
                    :read-result :owned-string
                    :argtypes [[:dataset :pointer]
                               [:options :pointer?]]}  ; GDALVectorInfoOptions*
   :GDALVectorInfoOptionsNew  {:rettype :pointer
                               :argtypes [[:argv :pointer?]
                                          [:for-binary :pointer?]]}
   :GDALVectorInfoOptionsFree {:rettype :void
                               :argtypes [[:options :pointer?]]}
   :GDALGetDescription {:rettype :string  ; the path of a dataset, the name of a driver
                        :argtypes [[:object :pointer]]}
   :GDALGetDriver {:rettype :pointer
                   :argtypes [[:idx :int32]]}
   :GDALGetDriverShortName {:rettype :string  ; for example "GTiff"
                            :argtypes [[:driver :pointer]]}
   :GDALGetDriverLongName {:rettype :string  ; for example "GeoTIFF"
                           :argtypes [[:driver :pointer]]}
   :GDALGetDatasetDriver {:rettype :pointer
                          :argtypes [[:dataset :pointer]]}
   :GDALGetFileList {:rettype :pointer  ; char**
                     :read-result :owned-string-list
                     :argtypes [[:dataset :pointer]]}

   :OGR_F_SetFieldNull {:rettype :void
                        :argtypes [[:feature :pointer]
                                   [:idx :int32]]}
   ;; tz-flag: 0 unknown, 1 local time, 100 GMT, 100 + n GMT plus n quarter
   ;; hours.
   :OGR_F_SetFieldDateTime {:rettype :void
                            :argtypes [[:feature :pointer]
                                       [:idx :int32]
                                       [:year :int32]
                                       [:month :int32]
                                       [:day :int32]
                                       [:hour :int32]
                                       [:minute :int32]
                                       [:second :int32]
                                       [:tz-flag :int32]]}
   :OGR_F_SetFieldDateTimeEx {:rettype :void
                              :argtypes [[:feature :pointer]
                                         [:idx :int32]
                                         [:year :int32]
                                         [:month :int32]
                                         [:day :int32]
                                         [:hour :int32]
                                         [:minute :int32]
                                         [:second :float32]
                                         [:tz-flag :int32]]}
   :OGR_F_GetFieldAsDateTime {:rettype :int32  ; 0 for a null field or one that is not a date
                              :argtypes [[:feature :pointer]
                                         [:idx :int32]
                                         [:year-out :pointer]
                                         [:month-out :pointer]
                                         [:day-out :pointer]
                                         [:hour-out :pointer]
                                         [:minute-out :pointer]
                                         [:second-out :pointer]
                                         [:tz-flag-out :pointer]]}
   :OGR_F_SetFieldBinary {:rettype :void
                          :argtypes [[:feature :pointer]
                                     [:idx :int32]
                                     [:n-bytes :int32]
                                     [:data :pointer]]}
   :OGR_F_GetFieldAsBinary {:rettype :pointer  ; GByte*
                            :read-result :bytes
                            :argtypes [[:feature :pointer]
                                       [:idx :int32]
                                       [:n-bytes-out :pointer]]}  ; int*
   :OGR_Fld_GetSubType {:rettype :int32  ; OFST*
                        :argtypes [[:fielddefn :pointer]]}
   :OGR_Fld_GetWidth {:rettype :int32  ; 0 when the field has no width
                      :argtypes [[:fielddefn :pointer]]}
   :OGR_Fld_GetPrecision {:rettype :int32
                          :argtypes [[:fielddefn :pointer]]}
   :OGR_L_SetFeature {:rettype :int32  ; OGRErr
                      :argtypes [[:layer :pointer]
                                 [:feature :pointer]]}
   :GDALDatasetRollbackTransaction {:rettype :int32  ; OGRErr
                                    :argtypes [[:dataset :pointer]]}
   :VSIGetMemFileBuffer {:rettype :pointer  ; GByte*, NULL when there is no file
                         :read-result :bytes
                         :argtypes [[:filename :string]
                                    [:length-out :pointer]  ; vsi_l_offset*
                                    [:unlink-and-seize :int32]]}  ; 0 in JS: the worker copies the buffer
   :VSIReadDir {:rettype :pointer  ; char**
                :read-result :owned-string-list
                :argtypes [[:path :string]]}
   :OSRIsSame {:rettype :int32  ; 1 for the same SRS
               :argtypes [[:srs :pointer]
                          [:other :pointer]]}
   :OSRIsGeographic {:rettype :int32
                     :argtypes [[:srs :pointer]]}
   :OSRIsProjected {:rettype :int32
                    :argtypes [[:srs :pointer]]}
   :OSRGetName {:rettype :string?  ; for example "WGS 84"
                :argtypes [[:srs :pointer]]}
   :OSRClone {:rettype :pointer  ; free with OSRDestroySpatialReference
              :argtypes [[:srs :pointer]]}
   :OSRAutoIdentifyEPSG {:rettype :int32  ; OGRErr
                         :argtypes [[:srs :pointer]]}
   :OSRExportToWktEx {:rettype :int32  ; OGRErr
                      :argtypes [[:srs :pointer]
                                 [:wkt-out :pointer]      ; char** out
                                 [:options :pointer?]]}

   ;; The feature owns each list, and its next call can change it.
   :OGR_F_GetFieldAsIntegerList {:rettype :pointer  ; const int*
                                 :argtypes [[:feature :pointer]
                                            [:idx :int32]
                                            [:count-out :pointer]]}  ; int*
   :OGR_F_GetFieldAsInteger64List {:rettype :pointer  ; const GIntBig*
                                   :argtypes [[:feature :pointer]
                                              [:idx :int32]
                                              [:count-out :pointer]]}  ; int*
   :OGR_F_GetFieldAsDoubleList {:rettype :pointer  ; const double*
                                :argtypes [[:feature :pointer]
                                           [:idx :int32]
                                           [:count-out :pointer]]}  ; int*
   :OGR_F_GetFieldAsStringList {:rettype :pointer  ; char**, NULL-terminated
                                :argtypes [[:feature :pointer]
                                           [:idx :int32]]}
   :OGR_F_SetFieldIntegerList {:rettype :void
                               :argtypes [[:feature :pointer]
                                          [:idx :int32]
                                          [:count :int32]
                                          [:values :pointer]]}  ; const int*
   :OGR_F_SetFieldInteger64List {:rettype :void
                                 :argtypes [[:feature :pointer]
                                            [:idx :int32]
                                            [:count :int32]
                                            [:values :pointer]]}  ; const GIntBig*
   :OGR_F_SetFieldDoubleList {:rettype :void
                              :argtypes [[:feature :pointer]
                                         [:idx :int32]
                                         [:count :int32]
                                         [:values :pointer]]}  ; const double*
   :OGR_F_SetFieldStringList {:rettype :void
                              :argtypes [[:feature :pointer]
                                         [:idx :int32]
                                         [:values :pointer]]}  ; CSLConstList
   :OGR_F_GetFieldAsDateTimeEx {:rettype :int32  ; 0 for a null field or one that is not a date
                                :argtypes [[:feature :pointer]
                                           [:idx :int32]
                                           [:year-out :pointer]
                                           [:month-out :pointer]
                                           [:day-out :pointer]
                                           [:hour-out :pointer]
                                           [:minute-out :pointer]
                                           [:second-out :pointer]  ; float*
                                           [:tz-flag-out :pointer]]}
   :OGR_L_SetIgnoredFields {:rettype :int32  ; OGRErr
                            :argtypes [[:layer :pointer]
                                       [:names :pointer?]]}  ; const char**, nil clears
   :OGR_L_DeleteFeature {:rettype :int32  ; OGRErr
                         :argtypes [[:layer :pointer]
                                    [:fid :int64]]}
   ;; A setter fails on the sealed field definitions of a layer (RFC 97).
   ;; Call it before OGR_L_CreateField.
   :OGR_Fld_SetWidth {:rettype :void
                      :argtypes [[:fielddefn :pointer]
                                 [:width :int32]]}
   :OGR_Fld_SetPrecision {:rettype :void
                          :argtypes [[:fielddefn :pointer]
                                     [:precision :int32]]}

   :OGR_F_IsFieldSet {:rettype :int32
                      :argtypes [[:feature :pointer]
                                 [:idx :int32]]}
   :OGR_F_UnsetField {:rettype :void
                      :argtypes [[:feature :pointer]
                                 [:idx :int32]]}
   :OGR_FD_GetGeomFieldCount {:rettype :int32
                              :argtypes [[:featuredefn :pointer]]}
   :OGR_FD_GetGeomFieldDefn {:rettype :pointer  ; borrowed OGRGeomFieldDefnH
                             :argtypes [[:featuredefn :pointer]
                                        [:idx :int32]]}
   :OGR_FD_GetGeomFieldIndex {:rettype :int32  ; -1 for an unknown name
                              :argtypes [[:featuredefn :pointer]
                                         [:name :string]]}
   :OGR_F_GetGeomFieldRef {:rettype :pointer  ; borrowed, NULL for no geometry
                           :argtypes [[:feature :pointer]
                                      [:idx :int32]]}
   :OGR_GFld_GetNameRef {:rettype :string
                         :argtypes [[:geomfielddefn :pointer]]}
   :OGR_GFld_GetType {:rettype :int32  ; OGRwkbGeometryType
                      :argtypes [[:geomfielddefn :pointer]]}
   :OGR_GFld_GetSpatialRef {:rettype :pointer  ; NULL for no SRS
                            :argtypes [[:geomfielddefn :pointer]]}
   :OGR_GFld_Create {:rettype :pointer  ; free with OGR_GFld_Destroy
                     :argtypes [[:name :string]
                                [:geom-type :int32]]}  ; OGRwkbGeometryType
   :OGR_GFld_Destroy {:rettype :void
                      :argtypes [[:geomfielddefn :pointer]]}
   :OGR_GFld_SetSpatialRef {:rettype :void
                            :argtypes [[:geomfielddefn :pointer]
                                       [:srs :pointer?]]}  ; nil removes the SRS
   :OGR_GFld_SetNullable {:rettype :void
                          :argtypes [[:geomfielddefn :pointer]
                                     [:nullable :int32]]}
   :OGR_L_CreateGeomField {:rettype :int32  ; OGRErr
                           :argtypes [[:layer :pointer]
                                      [:geomfielddefn :pointer]
                                      [:approx-ok :int32]]}
   :OGR_F_SetGeomFieldDirectly {:rettype :int32  ; OGRErr
                                :argtypes [[:feature :pointer]
                                           [:idx :int32]
                                           [:geometry :pointer?]]}  ; nil clears
   :OGR_F_SetGeomField {:rettype :int32  ; OGRErr
                        :argtypes [[:feature :pointer]
                                   [:idx :int32]
                                   [:geometry :pointer?]]}  ; nil clears
   :OGR_Fld_IsNullable {:rettype :int32
                        :argtypes [[:fielddefn :pointer]]}
   :OGR_Fld_SetNullable {:rettype :void
                         :argtypes [[:fielddefn :pointer]
                                    [:nullable :int32]]}
   :OGR_Fld_GetDefault {:rettype :string?  ; NULL for no default
                        :argtypes [[:fielddefn :pointer]]}
   :OGR_Fld_SetDefault {:rettype :void
                        :argtypes [[:fielddefn :pointer]
                                   [:default :string?]]}  ; nil removes the default
   :OGR_Fld_IsUnique {:rettype :int32
                      :argtypes [[:fielddefn :pointer]]}
   :OGR_Fld_SetUnique {:rettype :void
                       :argtypes [[:fielddefn :pointer]
                                  [:unique :int32]]}
   :OGR_Fld_GetTZFlag {:rettype :int32
                       :argtypes [[:fielddefn :pointer]]}
   :OGR_Fld_SetTZFlag {:rettype :void
                       :argtypes [[:fielddefn :pointer]
                                  [:tz-flag :int32]]}
   :OGR_L_UpsertFeature {:rettype :int32  ; OGRErr
                         :argtypes [[:layer :pointer]
                                    [:feature :pointer]]}
   :OGR_L_UpdateFeature {:rettype :int32  ; OGRErr
                         :argtypes [[:layer :pointer]
                                    [:feature :pointer]
                                    [:n-fields :int32]
                                    [:field-idxs :pointer?]       ; const int*
                                    [:n-geom-fields :int32]
                                    [:geom-field-idxs :pointer?]  ; const int*
                                    [:update-style-string :int32]]}  ; C bool
   :OGR_L_SyncToDisk {:rettype :int32  ; OGRErr
                      :argtypes [[:layer :pointer]]}
   :OGR_L_SetNextByIndex {:rettype :int32  ; OGRErr
                          :argtypes [[:layer :pointer]
                                     [:index :int64]]}
   :OGR_L_GetGeometryColumn {:rettype :string  ; "" when the layer has none
                             :argtypes [[:layer :pointer]]}
   :OGR_G_ExportToIsoWkt {:rettype :int32  ; OGRErr
                          :argtypes [[:geometry :pointer]
                                     [:wkt-out :pointer]]}  ; char** out
   :OGR_G_ExportToJsonEx {:rettype :pointer  ; char*, NULL for a failure
                          :read-result :owned-string
                          :argtypes [[:geometry :pointer]
                                     [:options :pointer?]]}  ; char**
   :OGR_G_GetEnvelope {:rettype :void
                       :argtypes [[:geometry :pointer]
                                  [:envelope-out :pointer]]}})
