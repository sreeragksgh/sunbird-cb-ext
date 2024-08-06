package org.sunbird.profile.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.sunbird.cache.RedisCacheMgr;
import org.sunbird.cassandra.utils.CassandraOperation;
import org.sunbird.common.model.SBApiResponse;
import org.sunbird.common.util.CbExtServerProperties;
import org.sunbird.common.util.Constants;
import org.sunbird.common.util.ProjectUtil;
import org.sunbird.storage.service.StorageService;
import org.sunbird.user.registration.model.UserRegistration;
import org.sunbird.user.service.UserUtilityService;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class UserBulkUploadService {
    private Logger logger = LoggerFactory.getLogger(UserBulkUploadService.class);
    ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    CbExtServerProperties serverProperties;
    @Autowired
    UserUtilityService userUtilityService;
    @Autowired
    CassandraOperation cassandraOperation;

    @Autowired
    StorageService storageService;

    @Autowired
    RedisCacheMgr redisCacheMgr;

    public void initiateUserBulkUploadProcess(String inputData) {
        logger.info("UserBulkUploadService:: initiateUserBulkUploadProcess: Started");
        long duration = 0;
        long startTime = System.currentTimeMillis();
        try {
            HashMap<String, String> inputDataMap = objectMapper.readValue(inputData,
                    new TypeReference<HashMap<String, String>>() {
                    });
            List<String> errList = validateReceivedKafkaMessage(inputDataMap);
            if (errList.isEmpty()) {
                updateUserBulkUploadStatus(inputDataMap.get(Constants.ROOT_ORG_ID),
                        inputDataMap.get(Constants.IDENTIFIER), Constants.STATUS_IN_PROGRESS_UPPERCASE, 0, 0, 0);
                String fileName = inputDataMap.get(Constants.FILE_NAME);
                logger.info("fileName {} ", fileName);
                storageService.downloadFile(fileName);
                switch (getFileExtension(fileName)) {
                    case Constants.CSV_FILE:
                        processCSVBulkUploadV2(inputDataMap);
                        break;
                    case Constants.XLSX_FILE:
                        processBulkUpload(inputDataMap);
                        break;
                    default:
                        logger.error("Unsupported file type: {}", fileName);
                }
            } else {
                logger.error(String.format("Error in the Kafka Message Received : %s", errList));
            }
        } catch (Exception e) {
            logger.error(String.format("Error in the scheduler to upload bulk users %s", e.getMessage()),
                    e);
        }
        duration = System.currentTimeMillis() - startTime;
        logger.info("UserBulkUploadService:: initiateUserBulkUploadProcess: Completed. Time taken: "
                + duration + " milli-seconds");
    }

    public void updateUserBulkUploadStatus(String rootOrgId, String identifier, String status, int totalRecordsCount,
                                           int successfulRecordsCount, int failedRecordsCount) {
        try {
            Map<String, Object> compositeKeys = new HashMap<>();
            compositeKeys.put(Constants.ROOT_ORG_ID_LOWER, rootOrgId);
            compositeKeys.put(Constants.IDENTIFIER, identifier);
            Map<String, Object> fieldsToBeUpdated = new HashMap<>();
            if (!status.isEmpty()) {
                fieldsToBeUpdated.put(Constants.STATUS, status);
            }
            if (totalRecordsCount >= 0) {
                fieldsToBeUpdated.put(Constants.TOTAL_RECORDS, totalRecordsCount);
            }
            if (successfulRecordsCount >= 0) {
                fieldsToBeUpdated.put(Constants.SUCCESSFUL_RECORDS_COUNT, successfulRecordsCount);
            }
            if (failedRecordsCount >= 0) {
                fieldsToBeUpdated.put(Constants.FAILED_RECORDS_COUNT, failedRecordsCount);
            }
            fieldsToBeUpdated.put(Constants.DATE_UPDATE_ON, new Timestamp(System.currentTimeMillis()));
            cassandraOperation.updateRecord(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_USER_BULK_UPLOAD,
                    fieldsToBeUpdated, compositeKeys);
        } catch (Exception e) {
            logger.error(String.format("Error in Updating User Bulk Upload Status in Cassandra %s", e.getMessage()), e);
        }
    }

    private void processBulkUpload(HashMap<String, String> inputDataMap) throws IOException {
        File file = null;
        FileInputStream fis = null;
        XSSFWorkbook wb = null;
        int totalRecordsCount = 0;
        int noOfSuccessfulRecords = 0;
        int failedRecordsCount = 0;
        String status = "";
        String phone = "";
        try {
            file = new File(Constants.LOCAL_BASE_PATH + inputDataMap.get(Constants.FILE_NAME));
            if (file.exists() && file.length() > 0) {
                fis = new FileInputStream(file);
                wb = new XSSFWorkbook(fis);
                XSSFSheet sheet = wb.getSheetAt(0);
                Iterator<Row> rowIterator = sheet.iterator();
                // incrementing the iterator inorder to skip the headers in the first row
                if (rowIterator.hasNext()) {
                    Row firstRow = rowIterator.next();
                    Cell statusCell = firstRow.getCell(14);
                    Cell errorDetails = firstRow.getCell(15);
                    if (statusCell == null) {
                        statusCell = firstRow.createCell(14);
                    }
                    if (errorDetails == null) {
                        errorDetails = firstRow.createCell(15);
                    }
                    statusCell.setCellValue("Status");
                    errorDetails.setCellValue("Error Details");
                }
                int count = 0;
                while (rowIterator.hasNext()) {
                    logger.info("UserBulkUploadService:: Record " + count++);
                    long duration = 0;
                    long startTime = System.currentTimeMillis();
                    StringBuffer str = new StringBuffer();
                    List<String> errList = new ArrayList<>();
                    List<String> invalidErrList = new ArrayList<>();
                    Row nextRow = rowIterator.next();
                    UserRegistration userRegistration = new UserRegistration();
                    if (nextRow.getCell(0) == null || nextRow.getCell(0).getCellType() == CellType.BLANK) {
                        errList.add("Full Name");
                    } else {
                        if (nextRow.getCell(0).getCellType() == CellType.STRING) {
                            userRegistration.setFirstName(nextRow.getCell(0).getStringCellValue().trim());
                            if (!ProjectUtil.validateFullName(userRegistration.getFirstName())) {
                                invalidErrList.add("Invalid Full Name");
                            }
                        } else {
                            invalidErrList.add("Invalid value for Full Name column type. Expecting string format");
                        }
                    }
                    if (nextRow.getCell(1) == null || nextRow.getCell(1).getCellType() == CellType.BLANK) {
                        errList.add("Email");
                    } else {
                        if (nextRow.getCell(1).getCellType() == CellType.STRING) {
                            userRegistration.setEmail(nextRow.getCell(1).getStringCellValue().trim());
                            if (ProjectUtil.validatesNewLine(userRegistration.getEmail())) {
                                invalidErrList.add("Invalid Email: Email address contains newline characters");
                            }
                        } else {
                            invalidErrList.add("Invalid value for Email column type. Expecting string format");
                        }
                    }
                    if (nextRow.getCell(2) == null || nextRow.getCell(2).getCellType() == CellType.BLANK) {
                        errList.add("Mobile Number");
                    } else {
                        if (nextRow.getCell(2).getCellType() == CellType.NUMERIC) {
                            phone = NumberToTextConverter.toText(nextRow.getCell(2).getNumericCellValue());
                            userRegistration.setPhone(phone.trim());
                        } else if (nextRow.getCell(2).getCellType() == CellType.STRING) {
                            phone = nextRow.getCell(2).getStringCellValue();
                            userRegistration.setPhone(phone.trim());
                        } else {
                            invalidErrList.add("Invalid value for Mobile Number column type. Expecting number/string format");
                        }
                        if (ProjectUtil.validatesNewLine(userRegistration.getPhone())) {
                            invalidErrList.add("Invalid Phone: Phone Number contains newline characters");
                        }
                    }
                    if (StringUtils.isNotBlank(phone)) {
                        if (!ProjectUtil.validateContactPattern(phone)) {
                            invalidErrList.add("The Mobile Number provided is Invalid");
                        }
                    }
                    if (nextRow.getCell(3) == null || nextRow.getCell(3).getCellType() == CellType.BLANK) {
                        errList.add("Group");
                    } else {
                        if (nextRow.getCell(3).getCellType() == CellType.STRING) {
                            userRegistration.setGroup(nextRow.getCell(3).getStringCellValue().trim());
                            if (ProjectUtil.validatesNewLine(userRegistration.getGroup())) {
                                invalidErrList.add("Invalid Group: Group contains newline characters");
                            }
                            if (!userUtilityService.validateGroup(userRegistration.getGroup())) {
                                invalidErrList.add("Invalid Group : Group can be only among one of these " + serverProperties.getBulkUploadGroupValue());
                            }
                        } else {
                            invalidErrList.add("Invalid value for Group Name column type. Expecting string format");
                        }
                    }
                    if (nextRow.getCell(4) == null || nextRow.getCell(4).getCellType() == CellType.BLANK) {
                        errList.add("Designation");
                    } else {
                        if (nextRow.getCell(4).getCellType() == CellType.STRING) {
                            userRegistration.setPosition(nextRow.getCell(4).getStringCellValue().trim());
                            if (ProjectUtil.validatesNewLine(userRegistration.getPosition())) {
                                invalidErrList.add("Invalid Position: Position contains newline characters");
                            }
                        } else {
                            invalidErrList.add("Invalid value for Designation column type. Expecting string format");
                        }
                        if (StringUtils.isNotBlank(userRegistration.getPosition())) {
                            if (!ProjectUtil.validateRegexPatternWithNoSpecialCharacter(userRegistration.getPosition()) || this.validateFieldValue(Constants.POSITION, userRegistration.getPosition())) {
                                invalidErrList.add("Invalid Designation: Designation should be added from default list and/or cannot contain special character");
                            }
                        }
                    }
                    if (nextRow.getCell(5) != null && nextRow.getCell(5).getCellType() != CellType.BLANK) {
                        if (nextRow.getCell(5).getCellType() == CellType.STRING) {
                            if (userUtilityService.validateGender(nextRow.getCell(5).getStringCellValue().trim())) {
                                userRegistration.setGender(nextRow.getCell(5).getStringCellValue().trim());
                                if (ProjectUtil.validatesNewLine(userRegistration.getGender())) {
                                    invalidErrList.add("Invalid Gender: Gender contains newline characters");
                                }
                            } else {
                                invalidErrList.add("Invalid Gender : Gender can be only among one of these " + serverProperties.getBulkUploadGenderValue());
                            }
                        } else {
                            invalidErrList.add("Invalid value for Gender column type. Expecting string format");
                        }
                    }
                    if (nextRow.getCell(6) != null && nextRow.getCell(6).getCellType() != CellType.BLANK) {
                        if (nextRow.getCell(6).getCellType() == CellType.STRING) {
                            if (userUtilityService.validateCategory(nextRow.getCell(6).getStringCellValue().trim())) {
                                userRegistration.setCategory(nextRow.getCell(6).getStringCellValue().trim());
                                if (ProjectUtil.validatesNewLine(userRegistration.getCategory())) {
                                    invalidErrList.add("Invalid Category: Category contains newline characters");
                                }
                            } else {
                                invalidErrList.add("Invalid Category : Category can be only among one of these " + serverProperties.getBulkUploadCategoryValue());
                            }
                        } else {
                            invalidErrList.add("Invalid value for Category column type. Expecting string format");
                        }
                    }
                    if (nextRow.getCell(7) != null && nextRow.getCell(7).getCellType() != CellType.BLANK) {
                        if (nextRow.getCell(7).getCellType() == CellType.STRING) {
                            if (ProjectUtil.validateDate(nextRow.getCell(7).getStringCellValue().trim())) {
                                userRegistration.setDob(nextRow.getCell(7).getStringCellValue().trim());
                            } else {
                                invalidErrList.add("Invalid format for Date of Birth type. Expecting in dd-mm-yyyy format");
                            }
                        } else if (nextRow.getCell(7).getCellType() == CellType.NUMERIC || DateUtil.isCellDateFormatted(nextRow.getCell(7))) {
                            Date date = nextRow.getCell(7).getDateCellValue();
                            SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
                            String dob = dateFormat.format(date);
                            if (ProjectUtil.validateDate(dob)) {
                                userRegistration.setDob(dob);
                            } else {
                                invalidErrList.add("Invalid format for Date of Birth type. Expecting in dd-mm-yyyy format");
                            }
                        } else {
                            invalidErrList.add("Invalid value for Date of Birth column type. Expecting string type in dd-mm-yyyy format");
                        }
                        if (ProjectUtil.validatesNewLine(userRegistration.getDob())) {
                            invalidErrList.add("Invalid DOB: DOB contains newline characters");
                        }
                    }
                    if (nextRow.getCell(8) != null && nextRow.getCell(8).getCellType() != CellType.BLANK) {
                        if (nextRow.getCell(8).getCellType() == CellType.STRING) {
                            userRegistration.setDomicileMedium(nextRow.getCell(8).getStringCellValue().trim());
                            if (ProjectUtil.validatesNewLine(userRegistration.getDomicileMedium())) {
                                invalidErrList.add("Invalid Domicile Medium: Domicile Medium contains newline characters");
                            }
                        } else {
                            invalidErrList.add("Invalid value for Mother Tongue column type. Expecting string format");
                        }
                        if (StringUtils.isNotBlank(userRegistration.getDomicileMedium())) {
                            if (!ProjectUtil.validateRegexPatternWithNoSpecialCharacter(userRegistration.getDomicileMedium()) || this.validateFieldValue(Constants.LANGUAGES, userRegistration.getDomicileMedium())) {
                                invalidErrList.add("Invalid Mother Tongue: Mother Tongue should be added from default list and/or cannot contain special character");
                            }
                        }
                    }
                    if (nextRow.getCell(9) != null && nextRow.getCell(9).getCellType() != CellType.BLANK) {
                        if (nextRow.getCell(9).getCellType() == CellType.NUMERIC) {
                            userRegistration.setEmployeeId(NumberToTextConverter.toText(nextRow.getCell(9).getNumericCellValue()).trim());
                        } else if (nextRow.getCell(9).getCellType() == CellType.STRING) {
                            userRegistration.setEmployeeId(nextRow.getCell(9).getStringCellValue().trim());
                        } else {
                            invalidErrList.add("Invalid value for Employee ID column type. Expecting string/number format");
                        }
                        if (StringUtils.isNotBlank(userRegistration.getEmployeeId())) {
                            if (!ProjectUtil.validateEmployeeId(userRegistration.getEmployeeId())) {
                                invalidErrList.add("Invalid Employee ID : Employee ID can contain alphabetic, alphanumeric or numeric character(s) and have a max length of 30");
                            }
                            if (userRegistration.getEmployeeId().contains(Constants.SPACE)) {
                                invalidErrList.add("Invalid Employee ID : Employee Id cannot contain spaces");
                            }
                        }
                        if (ProjectUtil.validatesNewLine(userRegistration.getEmployeeId())) {
                            invalidErrList.add("Invalid Employee ID: Employee ID contains newline characters");
                        }
                    }
                    if (nextRow.getCell(10) != null && nextRow.getCell(10).getCellType() != CellType.BLANK) {
                        if (nextRow.getCell(10).getCellType() == CellType.NUMERIC) {
                            userRegistration.setPincode(NumberToTextConverter.toText(nextRow.getCell(10).getNumericCellValue()));
                        } else if (nextRow.getCell(10).getCellType() == CellType.STRING) {
                            userRegistration.setPincode(nextRow.getCell(10).getStringCellValue().trim());
                        } else {
                            invalidErrList.add("Invalid value for Office Pin Code column type. Expecting number/string format");
                        }
                        if (StringUtils.isNotBlank(userRegistration.getPincode())) {
                            if (!ProjectUtil.validatePinCode(userRegistration.getPincode())) {
                                invalidErrList.add("Invalid Office Pin Code : Office Pin Code should be numeric and is of 6 digit.");
                            }
                        }
                        if (ProjectUtil.validatesNewLine(userRegistration.getPincode())) {
                            invalidErrList.add("Invalid Pin Code: Pin Code contains newline characters");
                        }
                    }
                    if (nextRow.getCell(11) != null && nextRow.getCell(11).getCellType() != CellType.BLANK) {
                        if (nextRow.getCell(11).getCellType() == CellType.NUMERIC) {
                            userRegistration.setExternalSystemId(NumberToTextConverter.toText(nextRow.getCell(11).getNumericCellValue()).trim());
                            if (!ProjectUtil.validateExternalSystemId(userRegistration.getExternalSystemId())) {
                                invalidErrList.add("Invalid External System ID : External System Id can contain alphanumeric characters and have a max length of 30");
                            }
                        } else if (nextRow.getCell(11).getCellType() == CellType.STRING) {
                            userRegistration.setExternalSystemId(nextRow.getCell(11).getStringCellValue().trim());
                            if (!ProjectUtil.validateExternalSystemId(userRegistration.getExternalSystemId())) {
                                invalidErrList.add("Invalid External System ID : External System Id can contain alphanumeric characters and have a max length of 30");
                            }
                        } else {
                            invalidErrList.add("Invalid value for External System ID column type. Expecting string/number format");
                        }
                        if (ProjectUtil.validatesNewLine(userRegistration.getExternalSystemId())) {
                            invalidErrList.add("Invalid External System ID: External System ID contains newline characters");
                        }
                    }
                    if (nextRow.getCell(12) != null && !StringUtils.isBlank(nextRow.getCell(12).toString())) {
                        if (nextRow.getCell(12).getCellType() == CellType.STRING) {
                            userRegistration.setExternalSystem(nextRow.getCell(12).getStringCellValue().trim());
                            if (!ProjectUtil.validateExternalSystem(userRegistration.getExternalSystem())) {
                                invalidErrList.add("Invalid External System Name : External System Name can contain only alphabets and alphanumeric and can have a max length of 255");
                            }
                            if (ProjectUtil.validatesNewLine(userRegistration.getExternalSystem())) {
                                invalidErrList.add("Invalid External System Name: External System Name contains newline characters");
                            }
                        } else {
                            invalidErrList.add("Invalid value for External System Name column type. Expecting string format");
                        }
                    }
                    if (nextRow.getCell(13) != null && nextRow.getCell(13).getCellType() != CellType.BLANK) {
                        if (nextRow.getCell(13).getCellType() == CellType.STRING) {
                            String tagStr = nextRow.getCell(13).getStringCellValue().trim();
                            List<String> tagList = new ArrayList<String>();
                            if (!StringUtils.isEmpty(tagStr)) {
                                String[] tagStrList = tagStr.split(",", -1);
                                for (String tag : tagStrList) {
                                    tagList.add(tag.trim());
                                }
                            }
                            userRegistration.setTag(tagList);
                            if (!ProjectUtil.validateTag(userRegistration.getTag())) {
                                invalidErrList.add("Invalid Tag : Tags are comma seperated string values. A Tag can contain only alphabets with spaces. eg: Bihar Circle, Patna Division");
                            }
                        } else {
                            invalidErrList.add("Invalid value for Tags column type. Expecting string format");
                        }
                    }
                    userRegistration.setOrgName(inputDataMap.get(Constants.ORG_NAME));
                    userRegistration.setChannel(inputDataMap.get(Constants.ORG_NAME));
                    userRegistration.setSbOrgId(inputDataMap.get(Constants.ROOT_ORG_ID));
                    Cell statusCell = nextRow.getCell(14);
                    Cell errorDetails = nextRow.getCell(15);
                    if (statusCell == null) {
                        statusCell = nextRow.createCell(14);
                    }
                    if (errorDetails == null) {
                        errorDetails = nextRow.createCell(15);
                    }
                    if (totalRecordsCount == 0 && errList.size() == 4) {
                        setErrorDetails(str, errList, statusCell, errorDetails);
                        failedRecordsCount++;
                        break;
                    } else if (totalRecordsCount > 0 && errList.size() == 4) {
                        break;
                    }
                    totalRecordsCount++;
                    if (!errList.isEmpty()) {
                        setErrorDetails(str, errList, statusCell, errorDetails);
                        failedRecordsCount++;
                    } else {
                        invalidErrList.addAll(validateEmailContactAndDomain(userRegistration));
                        if (invalidErrList.isEmpty()) {
                            userRegistration.setUserAuthToken(inputDataMap.get(Constants.X_AUTH_TOKEN));
                            String responseCode = userUtilityService.createBulkUploadUser(userRegistration);
                            if (!Constants.OK.equalsIgnoreCase(responseCode)) {
                                failedRecordsCount++;
                                statusCell.setCellValue(Constants.FAILED_UPPERCASE);
                                errorDetails.setCellValue(responseCode);
                            } else {
                                noOfSuccessfulRecords++;
                                statusCell.setCellValue(Constants.SUCCESS_UPPERCASE);
                                errorDetails.setCellValue("");
                            }
                        } else {
                            failedRecordsCount++;
                            statusCell.setCellValue(Constants.FAILED_UPPERCASE);
                            String invalidErrString = String.join(", ", invalidErrList);
                            errorDetails.setCellValue(invalidErrString);
                        }
                    }
                    duration = System.currentTimeMillis() - startTime;
                    logger.info("UserBulkUploadService:: Record Completed. Time taken: "
                            + duration + " milli-seconds");
                }
                if (totalRecordsCount == 0) {
                    XSSFRow row = sheet.createRow(sheet.getLastRowNum() + 1);
                    Cell statusCell = row.createCell(14);
                    Cell errorDetails = row.createCell(15);
                    statusCell.setCellValue(Constants.FAILED_UPPERCASE);
                    errorDetails.setCellValue(Constants.EMPTY_FILE_FAILED);

                }
                status = uploadTheUpdatedFile(file, wb);
                if (!(Constants.SUCCESSFUL.equalsIgnoreCase(status) && failedRecordsCount == 0
                        && totalRecordsCount == noOfSuccessfulRecords && totalRecordsCount >= 1)) {
                    status = Constants.FAILED_UPPERCASE;
                }
            } else {
                logger.info("Error in Process Bulk Upload : The File is not downloaded/present");
                status = Constants.FAILED_UPPERCASE;
            }
            updateUserBulkUploadStatus(inputDataMap.get(Constants.ROOT_ORG_ID), inputDataMap.get(Constants.IDENTIFIER),
                    status, totalRecordsCount, noOfSuccessfulRecords, failedRecordsCount);
        } catch (Exception e) {
            logger.error(String.format("Error in Process Bulk Upload %s", e.getMessage()), e);
            updateUserBulkUploadStatus(inputDataMap.get(Constants.ROOT_ORG_ID), inputDataMap.get(Constants.IDENTIFIER),
                    Constants.FAILED_UPPERCASE, 0, 0, 0);
        } finally {
            if (wb != null)
                wb.close();
            if (fis != null)
                fis.close();
            if (file != null)
                file.delete();
        }
    }
    public void processCSVBulkUploadV2(HashMap<String, String> inputDataMap) throws IOException {
        File file = null;
        CSVParser csvParser = null;
       CSVPrinter csvPrinter = null;
        BufferedWriter bufferedWriter = null;
        FileWriter fileWriter = null;
       int totalRecordsCount = 0;
        int noOfSuccessfulRecords = 0;
        int failedRecordsCount = 0;
        String status = "";

       try {
           file = new File(Constants.LOCAL_BASE_PATH + inputDataMap.get(Constants.FILE_NAME));
            if (file.exists() && file.length() > 0) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
                csvParser = new CSVParser(reader, CSVFormat.INFORMIX_UNLOAD.withFirstRecordAsHeader());

                List<CSVRecord> csvRecords = csvParser.getRecords();
                List<Map<String, String>> updatedRecords = new ArrayList<>();
                List<String> headers = new ArrayList<>(csvParser.getHeaderNames());
                headers.replaceAll(header -> header.replaceAll("^\"|\"$", ""));

               if (!headers.contains("Status")) {
                   headers.add("Status");
                }
                if (!headers.contains("Error Details")) {
                    headers.add("Error Details");
                }

                for (CSVRecord record : csvRecords) {
                   Map<String, String> updatedRecord = new LinkedHashMap<>(record.toMap());
                    List<String> errList = new ArrayList<>();
                    List<String> invalidErrList = new ArrayList<>();

                    UserRegistration userRegistration = new UserRegistration();

                    if (isFieldEmpty(record, 0)) {
                        errList.add("Full Name");
                    } else {
                        String fullName = record.get(0).trim();
                        if (!fullName.matches("[a-zA-Z ]+")) {
                            invalidErrList.add("Invalid value for Full Name column type. Expecting string format");
                        } else {
                            userRegistration.setFirstName(fullName);
                            if (!ProjectUtil.validateFullName(userRegistration.getFirstName())) {
                                invalidErrList.add("Invalid Full Name");
                            }
                        }
                    }

                    if (isFieldEmpty(record, 1)) {
                        errList.add("Email");
                    } else {
                        String email = record.get(1).trim();
                        userRegistration.setEmail(email);
                    }


                    if (isFieldEmpty(record, 2)) {
                        errList.add("Mobile Number");
                    } else {
                        String phone = record.get(2).trim();
                        if (phone.matches("^\\d+$")) {
                            userRegistration.setPhone(phone);
                        } else {
                            invalidErrList.add("Invalid value for Mobile Number column type. Expecting number format");
                        }
                    }
                    if (!StringUtils.isBlank(userRegistration.getPhone()) && !ProjectUtil.validateContactPattern(userRegistration.getPhone())) {
                        invalidErrList.add("The Mobile Number provided is Invalid");
                    }

                    if (isFieldEmpty(record, 3)) {
                        errList.add("Group");
                    } else {
                        String group = record.get(3).trim();
                        userRegistration.setGroup(group);
                        if (!userUtilityService.validateGroup(userRegistration.getGroup())) {
                            invalidErrList.add("Invalid Group : Group can be only among one of these " + serverProperties.getBulkUploadGroupValue());
                        }
                    }

                    if (isFieldEmpty(record, 4)) {
                        errList.add("Designation");
                    } else {
                        String position = record.get(4).trim();
                        userRegistration.setPosition(position);
                        if (!ProjectUtil.validateRegexPatternWithNoSpecialCharacter(userRegistration.getPosition()) || this.validateFieldValue(Constants.POSITION, userRegistration.getPosition())) {
                            invalidErrList.add("Invalid Designation: Designation should be added from default list and/or cannot contain special character");
                        }
                    }

                    if (!isFieldEmpty(record, 5)) {
                        String gender = record.get(5).trim();
                        if (userUtilityService.validateGender(gender)) {
                            userRegistration.setGender(gender);
                        } else {
                            invalidErrList.add("Invalid Gender : Gender can be only among one of these " + serverProperties.getBulkUploadGenderValue());
                        }
                    }

                    if (!isFieldEmpty(record, 6)) {
                        String category = record.get(6).trim();
                        if (userUtilityService.validateCategory(category)) {
                            userRegistration.setCategory(category);
                        } else {
                            invalidErrList.add("Invalid Category : Category can be only among one of these " + serverProperties.getBulkUploadCategoryValue());
                        }
                    }

                    if (!isFieldEmpty(record, 7)) {
                        String dob = record.get(7).trim();
                        if (ProjectUtil.validateDate(dob)) {
                            userRegistration.setDob(dob);
                        } else {
                            invalidErrList.add("Invalid format for Date of Birth type. Expecting in dd-mm-yyyy format");
                        }
                   }

                    if (!isFieldEmpty(record, 8)) {
                        String motherTongue = record.get(8).trim();
                        userRegistration.setDomicileMedium(motherTongue);
                        if (!ProjectUtil.validateRegexPatternWithNoSpecialCharacter(userRegistration.getDomicileMedium()) || this.validateFieldValue(Constants.LANGUAGES, userRegistration.getDomicileMedium())) {
                            invalidErrList.add("Invalid Mother Tongue: Mother Tongue should be added from default list and/or cannot contain special character");
                        }
                    }

                   if (!isFieldEmpty(record, 9)) {
                        String employeeId = record.get(9).trim();
                        userRegistration.setEmployeeId(employeeId);
                        if (!ProjectUtil.validateEmployeeId(userRegistration.getEmployeeId())) {
                            invalidErrList.add("Invalid Employee ID : Employee ID can contain alphabetic, alphanumeric or numeric character(s) and have a max length of 30");
                        }
                        if (userRegistration.getEmployeeId().contains(Constants.SPACE)) {
                            invalidErrList.add("Employee Id cannot contain spaces");
                        }
                    }

                    if (!isFieldEmpty(record, 10)) {
                        String pincode = record.get(10).trim();
                        userRegistration.setPincode(pincode);
                        if (!ProjectUtil.validatePinCode(userRegistration.getPincode())) {
                            invalidErrList.add("Invalid Office Pin Code : Office Pin Code should be numeric and is of 6 digit.");
                        }
                    }

                    if (!isFieldEmpty(record, 11)) {
                        String externalSystemId = record.get(11).trim();
                        userRegistration.setExternalSystemId(externalSystemId);
                        if (!ProjectUtil.validateExternalSystemId(userRegistration.getExternalSystemId())) {
                            invalidErrList.add("Invalid External System ID : External System Id can contain alphanumeric characters and have a max length of 30");
                        }
                    }
                    if (!isFieldEmpty(record, 12)) {
                        String externalSystem = record.get(12).trim();
                        userRegistration.setExternalSystem(externalSystem);
                        if (!ProjectUtil.validateExternalSystem(userRegistration.getExternalSystem())) {
                            invalidErrList.add("Invalid External System Name : External System Name can contain only alphabets and alphanumeric and can have a max length of 255");
                        }
                    }

                    if (!isFieldEmpty(record, 13)) {
                        String tagStr = record.get(13).trim();
                        List<String> tagList = new ArrayList<>();
                        if (!StringUtils.isEmpty(tagStr)) {
                            String[] tagStrList = tagStr.split("&", -1);
                            for (String tag : tagStrList) {
                                tagList.add(tag.trim());
                            }
                        }
                        userRegistration.setTag(tagList);
                        if (!ProjectUtil.validateTag(userRegistration.getTag())) {
                            invalidErrList.add("Invalid Tag: Tags are separated by '&' and can contain only alphabets with spaces. e.g., Bihar Circle&Patna Division");
                        }
                    }
                    userRegistration.setOrgName(inputDataMap.get(Constants.ORG_NAME));
                    userRegistration.setChannel(inputDataMap.get(Constants.ORG_NAME));
                    userRegistration.setSbOrgId(inputDataMap.get(Constants.ROOT_ORG_ID));

                    if (totalRecordsCount == 0 && errList.size() == 4) {
                        updatedRecord.put("Status", "FAILED");
                        updatedRecord.put("Error Details", String.join(", ", errList));
                        failedRecordsCount++;
                        break;
                    } else if (totalRecordsCount > 0 && errList.size() == 4) {
                        break;
                    }
                    totalRecordsCount++;

                    if (!errList.isEmpty()) {
                        failedRecordsCount++;
                        updatedRecord.put("Status", "FAILED");
                        updatedRecord.put("Error Details", String.join(", ", errList));
                    } else {
                        invalidErrList.addAll(validateEmailContactAndDomain(userRegistration));
                        if (invalidErrList.isEmpty()) {
                            userRegistration.setUserAuthToken(inputDataMap.get(Constants.X_AUTH_TOKEN));
                          String responseCode = userUtilityService.createBulkUploadUser(userRegistration);
                            if (!Constants.OK.equalsIgnoreCase(responseCode)) {
                                failedRecordsCount++;
                                updatedRecord.put("Status", "FAILED");
                                updatedRecord.put("Error Details", responseCode);
                            } else {
                                noOfSuccessfulRecords++;
                                updatedRecord.put("Status", Constants.SUCCESSFUL_UPPERCASE);
                                updatedRecord.put("Error Details", "");
                            }
                        } else {
                            failedRecordsCount++;
                            updatedRecord.put("Status", "FAILED");
                            updatedRecord.put("Error Details", String.join(", ", invalidErrList));
                        }
                    }

                    updatedRecords.add(updatedRecord);
                }
                logger.info("total noOfSuccessfulRecords {}, total nofailedRecordsCount {}, and total totalRecordsCount {}", noOfSuccessfulRecords,failedRecordsCount,totalRecordsCount);
                // Write back updated records to the same CSV file
                 fileWriter = new FileWriter(file);
                bufferedWriter = new BufferedWriter(fileWriter);
                 csvPrinter = new CSVPrinter(bufferedWriter, CSVFormat.INFORMIX_UNLOAD.withHeader(headers.toArray(new String[0])));


                for (Map<String, String> record : updatedRecords) {
                    List<String> recordValues = new ArrayList<>();
                    for (String header :  headers) {
                        recordValues.add(record.get(header));
                    }
                    csvPrinter.printRecord(recordValues);
                }

                if (totalRecordsCount == 0) {
                    List<String> singleRow = new ArrayList<>(Collections.nCopies(headers.size(), ""));
                    singleRow.set(headers.indexOf("Status"), Constants.FAILED_UPPERCASE);
                    singleRow.set(headers.indexOf("Error Details"), Constants.EMPTY_FILE_FAILED);
                    csvPrinter.printRecord(singleRow);
                    status = Constants.FAILED_UPPERCASE;
                }
                csvPrinter.flush();

                status = uploadTheUpdatedCSVFile(file);

                if (!(Constants.SUCCESSFUL.equalsIgnoreCase(status) && failedRecordsCount == 0
                        && totalRecordsCount == noOfSuccessfulRecords && totalRecordsCount >= 1)) {
                    status = Constants.FAILED_UPPERCASE;
                }

            } else {
                logger.info("Error in Process Bulk Upload: The File is not downloaded/present");
                status = Constants.FAILED_UPPERCASE;
            }
           updateUserBulkUploadStatus(inputDataMap.get(Constants.ROOT_ORG_ID), inputDataMap.get(Constants.IDENTIFIER),
                   status, totalRecordsCount, noOfSuccessfulRecords, failedRecordsCount);
        } catch (Exception e) {
            logger.error(String.format("Error in Process Bulk Upload %s", e.getMessage()), e);
           updateUserBulkUploadStatus(inputDataMap.get(Constants.ROOT_ORG_ID), inputDataMap.get(Constants.IDENTIFIER),
                    Constants.FAILED_UPPERCASE, 0, 0, 0);
        } finally {
            if (csvParser != null)
               csvParser.close();
            if (csvPrinter != null)
                csvPrinter.close();
           if (bufferedWriter != null)
               bufferedWriter.close();
           if (fileWriter != null)
               fileWriter.close();
            if (file != null)
                file.delete();
        }
   }

    private boolean isFieldEmpty(CSVRecord record, int index) {
        return record.get(index) == null || record.get(index).trim().isEmpty();
    }


    private void setErrorDetails(StringBuffer str, List<String> errList, Cell statusCell, Cell errorDetails) {
        str.append("Failed to process user record. Missing Parameters - ").append(errList);
        statusCell.setCellValue(Constants.FAILED_UPPERCASE);
        errorDetails.setCellValue(str.toString());
    }

    private String uploadTheUpdatedFile(File file, XSSFWorkbook wb)
            throws IOException {
        FileOutputStream fileOut = new FileOutputStream(file);
        wb.write(fileOut);
        fileOut.close();
        SBApiResponse uploadResponse = storageService.uploadFile(file, serverProperties.getBulkUploadContainerName(),serverProperties.getCloudContainerName());
        if (!HttpStatus.OK.equals(uploadResponse.getResponseCode())) {
            logger.info(String.format("Failed to upload file. Error: %s",
                    uploadResponse.getParams().getErrmsg()));
            return Constants.FAILED_UPPERCASE;
        }
        return Constants.SUCCESSFUL_UPPERCASE;
    }

    private String uploadTheUpdatedCSVFile(File file)
            throws IOException {

        SBApiResponse uploadResponse = storageService.uploadFile(file, serverProperties.getBulkUploadContainerName(),serverProperties.getCloudContainerName());
        if (!HttpStatus.OK.equals(uploadResponse.getResponseCode())) {
            logger.info(String.format("Failed to upload file. Error: %s",
                    uploadResponse.getParams().getErrmsg()));
            return Constants.FAILED_UPPERCASE;
        }
        return Constants.SUCCESSFUL_UPPERCASE;
    }
    private List<String> validateEmailContactAndDomain(UserRegistration userRegistration) {
        StringBuffer str = new StringBuffer();
        List<String> errList = new ArrayList<>();
        if (!ProjectUtil.validateEmailPattern(userRegistration.getEmail())) {
            errList.add("Invalid Email Id");
        }
        if (!ProjectUtil.validateContactPattern(userRegistration.getPhone())) {
            errList.add("Invalid Mobile Number");
        }
        if (!errList.isEmpty()) {
            str.append("Failed to Validate User Details. Error Details - [").append(errList).append("]");
        }
        return errList;
    }

    private List<String> validateReceivedKafkaMessage(HashMap<String, String> inputDataMap) {
        StringBuffer str = new StringBuffer();
        List<String> errList = new ArrayList<>();
        if (StringUtils.isEmpty(inputDataMap.get(Constants.ROOT_ORG_ID))) {
            errList.add("RootOrgId is not present");
        }
        if (StringUtils.isEmpty(inputDataMap.get(Constants.IDENTIFIER))) {
            errList.add("Identifier is not present");
        }
        if (StringUtils.isEmpty(inputDataMap.get(Constants.FILE_NAME))) {
            errList.add("Filename is not present");
        }
        if (StringUtils.isEmpty(inputDataMap.get(Constants.ORG_NAME))) {
            errList.add("Orgname is not present");
        }
        if (StringUtils.isEmpty(inputDataMap.get(Constants.X_AUTH_TOKEN))) {
            errList.add("User Token is not present");
        }
        if (!errList.isEmpty()) {
            str.append("Failed to Validate User Details. Error Details - [").append(errList.toString()).append("]");
        }
        return errList;
    }

    private boolean validateFieldValue(String fieldKey, String fieldValue) {
        fieldValue = fieldValue.toLowerCase();
        if(redisCacheMgr.keyExists(fieldKey)){
            return !redisCacheMgr.valueExists(fieldKey, fieldValue);
        } else{
            Set<String> designationsSet = new HashSet<>();
            Map<String,Object> propertiesMap = new HashMap<>();
            propertiesMap.put(Constants.CONTEXT_TYPE, fieldKey);
            List<Map<String, Object>> fieldValueList = cassandraOperation.getRecordsByProperties(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_MASTER_DATA, propertiesMap, Collections.singletonList(Constants.CONTEXT_NAME));
            if(!CollectionUtils.isEmpty(fieldValueList)) {
                String columnName = fieldValueList.get(0).get("contextname") != null ? "contextname" : "contextName";
                for(Map<String, Object> languageMap : fieldValueList){
                    designationsSet.add(((String)languageMap.get(columnName)).toLowerCase());
                }
            }
            redisCacheMgr.putCacheAsStringArray(fieldKey, designationsSet.toArray(new String[0]), null);
            return !designationsSet.contains(fieldValue);
        }
    }
    private String getFileExtension(String fileName) {
        int lastIndexOfDot= fileName.lastIndexOf('.');
        return lastIndexOfDot == -1 ? "" : fileName.substring(lastIndexOfDot);
    }

}