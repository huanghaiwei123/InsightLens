package generator.entity;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import javax.validation.constraints.NotNull;

import java.io.Serializable;

import java.util.Date;
import io.swagger.annotations.ApiModelProperty;
import org.hibernate.validator.constraints.Length;

/**
* Excel 上传批次表：每次上传一行；Excel 是快照不是流，旧批次不删
* @TableName upload_batch
*/
public class UploadBatch implements Serializable {

    /**
    * 批次记录id（自增主键）
    */
    @NotNull(message="[批次记录id（自增主键）]不能为空")
    @ApiModelProperty("批次记录id（自增主键）")
    private Integer id;
    /**
    * 批次业务标识（UUID），同时用于生成安全表名 u_<upload_id>_xxx 与数据文件名
    */
    @NotBlank(message="[批次业务标识（UUID），同时用于生成安全表名 u_<upload_id>_xxx 与数据文件名]不能为空")
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("批次业务标识（UUID），同时用于生成安全表名 u_<upload_id>_xxx 与数据文件名")
    @Length(max= 0,message="编码长度不能超过0")
    private String uploadId;
    /**
    * 原始文件名（含中文/括号，仅用于展示，绝不直接作为表名）
    */
    @NotBlank(message="[原始文件名（含中文/括号，仅用于展示，绝不直接作为表名）]不能为空")
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("原始文件名（含中文/括号，仅用于展示，绝不直接作为表名）")
    @Length(max= 0,message="编码长度不能超过0")
    private String origName;
    /**
    * 本批次生成的物理表名列表，JSON 数组字符串，如 ["u_ab12_sales","u_ab12_refund"]（一个 sheet 一张表）
    */
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("本批次生成的物理表名列表，JSON 数组字符串，如 ["u_ab12_sales","u_ab12_refund"]（一个 sheet 一张表）")
    @Length(max= 0,message="编码长度不能超过0")
    private String tableNames;
    /**
    * 导入总行数（全部 sheet 求和），用于回答里回显"基于 N 行数据"
    */
    @ApiModelProperty("导入总行数（全部 sheet 求和），用于回答里回显"基于 N 行数据"")
    private Long rowCount;
    /**
    * 对应 DuckDB 数据文件的相对路径，删除批次时据此删文件
    */
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("对应 DuckDB 数据文件的相对路径，删除批次时据此删文件")
    @Length(max= 0,message="编码长度不能超过0")
    private String filePath;
    /**
    * 状态：pending / parsing / success / failed / deleted
    */
    @NotBlank(message="[状态：pending / parsing / success / failed / deleted]不能为空")
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("状态：pending / parsing / success / failed / deleted")
    @Length(max= 0,message="编码长度不能超过0")
    private String status;
    /**
    * 上传时间；回答时必须回显"基于 X 时间上传的数据"
    */
    @NotNull(message="[上传时间；回答时必须回显"基于 X 时间上传的数据"]不能为空")
    @ApiModelProperty("上传时间；回答时必须回显"基于 X 时间上传的数据"")
    private Date createdAt;

    /**
    * 批次记录id（自增主键）
    */
    private void setId(Integer id){
    this.id = id;
    }

    /**
    * 批次业务标识（UUID），同时用于生成安全表名 u_<upload_id>_xxx 与数据文件名
    */
    private void setUploadId(String uploadId){
    this.uploadId = uploadId;
    }

    /**
    * 原始文件名（含中文/括号，仅用于展示，绝不直接作为表名）
    */
    private void setOrigName(String origName){
    this.origName = origName;
    }

    /**
    * 本批次生成的物理表名列表，JSON 数组字符串，如 ["u_ab12_sales","u_ab12_refund"]（一个 sheet 一张表）
    */
    private void setTableNames(String tableNames){
    this.tableNames = tableNames;
    }

    /**
    * 导入总行数（全部 sheet 求和），用于回答里回显"基于 N 行数据"
    */
    private void setRowCount(Long rowCount){
    this.rowCount = rowCount;
    }

    /**
    * 对应 DuckDB 数据文件的相对路径，删除批次时据此删文件
    */
    private void setFilePath(String filePath){
    this.filePath = filePath;
    }

    /**
    * 状态：pending / parsing / success / failed / deleted
    */
    private void setStatus(String status){
    this.status = status;
    }

    /**
    * 上传时间；回答时必须回显"基于 X 时间上传的数据"
    */
    private void setCreatedAt(Date createdAt){
    this.createdAt = createdAt;
    }


    /**
    * 批次记录id（自增主键）
    */
    private Integer getId(){
    return this.id;
    }

    /**
    * 批次业务标识（UUID），同时用于生成安全表名 u_<upload_id>_xxx 与数据文件名
    */
    private String getUploadId(){
    return this.uploadId;
    }

    /**
    * 原始文件名（含中文/括号，仅用于展示，绝不直接作为表名）
    */
    private String getOrigName(){
    return this.origName;
    }

    /**
    * 本批次生成的物理表名列表，JSON 数组字符串，如 ["u_ab12_sales","u_ab12_refund"]（一个 sheet 一张表）
    */
    private String getTableNames(){
    return this.tableNames;
    }

    /**
    * 导入总行数（全部 sheet 求和），用于回答里回显"基于 N 行数据"
    */
    private Long getRowCount(){
    return this.rowCount;
    }

    /**
    * 对应 DuckDB 数据文件的相对路径，删除批次时据此删文件
    */
    private String getFilePath(){
    return this.filePath;
    }

    /**
    * 状态：pending / parsing / success / failed / deleted
    */
    private String getStatus(){
    return this.status;
    }

    /**
    * 上传时间；回答时必须回显"基于 X 时间上传的数据"
    */
    private Date getCreatedAt(){
    return this.createdAt;
    }

}
