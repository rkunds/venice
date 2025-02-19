package com.linkedin.venice.compute;

import static com.linkedin.venice.serializer.FastSerializerDeserializerFactory.*;

import com.linkedin.avro.api.PrimitiveFloatList;
import com.linkedin.venice.VeniceConstants;
import com.linkedin.venice.compute.protocol.request.ComputeOperation;
import com.linkedin.venice.compute.protocol.request.ComputeRequest;
import com.linkedin.venice.compute.protocol.request.ComputeRequestV3;
import com.linkedin.venice.compute.protocol.request.CosineSimilarity;
import com.linkedin.venice.compute.protocol.request.Count;
import com.linkedin.venice.compute.protocol.request.DotProduct;
import com.linkedin.venice.compute.protocol.request.HadamardProduct;
import com.linkedin.venice.compute.protocol.request.enums.ComputeOperationType;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.serializer.RecordDeserializer;
import com.linkedin.venice.utils.CollectionUtils;
import com.linkedin.venice.utils.Pair;
import com.linkedin.venice.utils.RedundantExceptionFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * This class provides utilities for float-vector operations, and it also handles {@link PrimitiveFloatList}
 * transparently to the user of this class.
 */
public class ComputeUtils {
  private static final Logger LOGGER = LogManager.getLogger(ComputeUtils.class);
  public static final String CACHED_SQUARED_L2_NORM_KEY = "CACHED_SQUARED_L2_NORM_KEY";
  public static final Pattern VALID_AVRO_NAME_PATTERN = Pattern.compile("\\A[A-Za-z_][A-Za-z0-9_]*\\z");
  public static final String ILLEGAL_AVRO_CHARACTER = "[^A-Za-z0-9_]";
  public static final String ILLEGAL_AVRO_CHARACTER_REPLACEMENT = "_";
  private static final RedundantExceptionFilter REDUNDANT_EXCEPTION_FILTER =
      RedundantExceptionFilter.getRedundantExceptionFilter();

  /**
   * N.B.: This deserializer performs an evolution from the schema of {@link ComputeRequestV3} to that of
   * {@link ComputeRequest}, with the only difference between the two being that the items of the operations list
   * in the former are defined as a union of one type, which unfortunately results in the SpecificRecord typing this
   * as a {@link List<Object>}. This is a design shortcoming, but which we cannot easily fix, since there are already
   * clients using this protocol. On the server-side, however, we wish to use proper types without lots of casting,
   * which we can achieve by letting Avro do the evolution.
   */
  private static final RecordDeserializer<ComputeRequest> DESERIALIZER =
      getFastAvroSpecificDeserializer(ComputeRequestV3.SCHEMA$, ComputeRequest.class);

  public static ComputeRequest deserializeComputeRequest(BinaryDecoder decoder, ComputeRequest reuse) {
    return DESERIALIZER.deserialize(reuse, decoder);
  }

  public static void checkResultSchema(Schema resultSchema, Schema valueSchema, List<ComputeOperation> operations) {
    if (resultSchema.getType() != Schema.Type.RECORD || valueSchema.getType() != Schema.Type.RECORD) {
      throw new VeniceException("Compute result schema and value schema must be RECORD type");
    }

    final Map<String, Schema> valueFieldSchemaMap = new HashMap<>(valueSchema.getFields().size());
    valueSchema.getFields().forEach(f -> valueFieldSchemaMap.put(f.name(), f.schema()));
    Set<Pair<String, Schema.Type>> operationResultFields = new HashSet<>();

    for (ComputeOperation operation: operations) {
      switch (ComputeOperationType.valueOf(operation)) {
        case DOT_PRODUCT:
          DotProduct dotProduct = (DotProduct) operation.operation;
          if (!valueFieldSchemaMap.containsKey(dotProduct.field.toString())) {
            throw new VeniceException(
                "The field " + dotProduct.field.toString() + " being operated on is not in value schema");
          }
          operationResultFields.add(new Pair<>(dotProduct.resultFieldName.toString(), Schema.Type.UNION));
          break;
        case COSINE_SIMILARITY:
          CosineSimilarity cosineSimilarity = (CosineSimilarity) operation.operation;
          if (!valueFieldSchemaMap.containsKey(cosineSimilarity.field.toString())) {
            throw new VeniceException(
                "The field " + cosineSimilarity.field.toString() + " being operated on is not in value schema");
          }
          operationResultFields.add(new Pair<>(cosineSimilarity.resultFieldName.toString(), Schema.Type.UNION));
          break;
        case HADAMARD_PRODUCT:
          HadamardProduct hadamardProduct = (HadamardProduct) operation.operation;
          if (!valueFieldSchemaMap.containsKey(hadamardProduct.field.toString())) {
            throw new VeniceException(
                "The field " + hadamardProduct.field.toString() + " being operated on is not in value schema");
          }
          operationResultFields.add(new Pair<>(hadamardProduct.resultFieldName.toString(), Schema.Type.UNION));
          break;
        case COUNT:
          Count count = (Count) operation.operation;
          if (!valueFieldSchemaMap.containsKey(count.field.toString())) {
            throw new VeniceException(
                "The field " + count.field.toString() + " being operated on is not in value schema");
          }
          operationResultFields.add(new Pair<>(count.resultFieldName.toString(), Schema.Type.UNION));
          break;
        default:
          throw new VeniceException("Compute operation type " + operation.operationType + " not supported");
      }
    }
    for (Schema.Field resultField: resultSchema.getFields()) {
      /**
       * There is no need to compare whether the 'resultField' is exactly same as the corresponding one in the value schema,
       * since there is no need to make the result schema backward compatible.
       * As long as the schema of the same field is same between the result schema and the value schema, it will be
       * good enough.
       * The major reason we couldn't make sure the same field is exactly same between the result schema and value schema
       * is that it is not easy to achieve on Client side since the way to extract the default value from
       * an existing field changes with Avro-1.9 or above.
       */
      if (resultField.schema().equals(valueFieldSchemaMap.get(resultField.name()))) {
        continue;
      }
      if (resultField.name().equals(VeniceConstants.VENICE_COMPUTATION_ERROR_MAP_FIELD_NAME)) {
        continue;
      }
      Pair<String, Schema.Type> resultFieldPair = new Pair<>(resultField.name(), resultField.schema().getType());
      if (!operationResultFields.contains(resultFieldPair)) {
        String msg = "The result field " + resultField.name() + " with schema " + resultField.schema()
            + " for value schema code " + valueSchema.hashCode();
        if (!REDUNDANT_EXCEPTION_FILTER.isRedundantException(msg)) {
          LOGGER.error(
              msg + " is not a field in value schema or an operation result schema." + " Value schema " + valueSchema);
        }
        throw new VeniceException(
            "The result field " + resultField.name()
                + " is not a field in value schema or an operation result schema.");
      }
    }
  }

  /**
   * According to Avro specification (https://avro.apache.org/docs/1.7.7/spec.html#Names):
   *
   * The name portion of a fullname, record field names, and enum symbols must:
   *     1. start with [A-Za-z_]
   *     2. subsequently contain only [A-Za-z0-9_]
   *
   * Remove all Avro illegal characters.
   *
   * @param name
   * @return a string that doesn't contain any illegal character
   */
  public static String removeAvroIllegalCharacter(String name) {
    if (name == null) {
      throw new NullPointerException("The name parameter must be specified");
    }
    Matcher m = VALID_AVRO_NAME_PATTERN.matcher(name);
    if (m.matches()) {
      return name;
    }

    return name.replaceAll(ILLEGAL_AVRO_CHARACTER, ILLEGAL_AVRO_CHARACTER_REPLACEMENT);
  }

  public static float dotProduct(List<Float> list1, List<Float> list2) {
    if (list1.size() != list2.size()) {
      throw new VeniceException("Two lists are with different dimensions: " + list1.size() + ", and " + list2.size());
    }
    if (list1 instanceof PrimitiveFloatList && list2 instanceof PrimitiveFloatList) {
      PrimitiveFloatList primitiveFloatList1 = (PrimitiveFloatList) list1;
      PrimitiveFloatList primitiveFloatList2 = (PrimitiveFloatList) list2;
      return dotProduct(list1.size(), primitiveFloatList1::getPrimitive, primitiveFloatList2::getPrimitive);
    } else {
      return dotProduct(list1.size(), list1::get, list2::get);
    }
  }

  public static List<Float> hadamardProduct(List<Float> list1, List<Float> list2) {
    if (list1.size() != list2.size()) {
      throw new VeniceException("Two lists are with different dimensions: " + list1.size() + ", and " + list2.size());
    }
    if (list1 instanceof PrimitiveFloatList && list2 instanceof PrimitiveFloatList) {
      PrimitiveFloatList primitiveFloatList1 = (PrimitiveFloatList) list1;
      PrimitiveFloatList primitiveFloatList2 = (PrimitiveFloatList) list2;
      return hadamardProduct(list1.size(), primitiveFloatList1::getPrimitive, primitiveFloatList2::getPrimitive);
    } else {
      return hadamardProduct(list1.size(), list1::get, list2::get);
    }
  }

  public static List<Schema.Field> getOperationResultFields(List<ComputeOperation> operations, Schema resultSchema) {
    List<Schema.Field> operationResultFields = new ArrayList<>(operations.size());
    ComputeOperation computeOperation;
    ReadComputeOperator operator;
    for (int i = 0; i < operations.size(); i++) {
      computeOperation = operations.get(i);
      operator = ComputeOperationType.valueOf(computeOperation).getOperator();
      operationResultFields.add(resultSchema.getField(operator.getResultFieldName(computeOperation)));
    }
    return operationResultFields;
  }

  private interface FloatSupplierByIndex {
    float get(int index);
  }

  private static float dotProduct(int size, FloatSupplierByIndex floatSupplier1, FloatSupplierByIndex floatSupplier2) {
    float dotProductResult = 0.0f;

    // round up size to the largest multiple of 4
    int i = 0;
    int limit = (size >> 2) << 2;

    // Unrolling mult-add into blocks of 4 multiply op and assign to 4 different variables so that CPU can take
    // advantage of out of order execution, making the operation faster (on a single thread ~2x improvement)
    for (; i < limit; i += 4) {
      float s0 = floatSupplier1.get(i) * floatSupplier2.get(i);
      float s1 = floatSupplier1.get(i + 1) * floatSupplier2.get(i + 1);
      float s2 = floatSupplier1.get(i + 2) * floatSupplier2.get(i + 2);
      float s3 = floatSupplier1.get(i + 3) * floatSupplier2.get(i + 3);

      dotProductResult += (s0 + s1 + s2 + s3);
    }

    // Multiply the remaining elements
    for (; i < size; i++) {
      dotProductResult += floatSupplier1.get(i) * floatSupplier2.get(i);
    }
    return dotProductResult;
  }

  private static List<Float> hadamardProduct(
      int size,
      FloatSupplierByIndex floatSupplier1,
      FloatSupplierByIndex floatSupplier2) {
    float[] floats = new float[size];

    // round up size to the largest multiple of 4
    int i = 0;
    int limit = (size >> 2) << 2;

    // Unrolling mult-add into blocks of 4 multiply op and assign to 4 different variables so that CPU can take
    // advantage of out of order execution, making the operation faster (on a single thread ~2x improvement)
    for (; i < limit; i += 4) {
      floats[i] = floatSupplier1.get(i) * floatSupplier2.get(i);
      floats[i + 1] = floatSupplier1.get(i + 1) * floatSupplier2.get(i + 1);
      floats[i + 2] = floatSupplier1.get(i + 2) * floatSupplier2.get(i + 2);
      floats[i + 3] = floatSupplier1.get(i + 3) * floatSupplier2.get(i + 3);
    }

    // Multiply the remaining elements
    for (; i < size; i++) {
      floats[i] = floatSupplier1.get(i) * floatSupplier2.get(i);
    }
    return CollectionUtils.asUnmodifiableList(floats);
  }

  public static float squaredL2Norm(List<Float> list) {
    if (list instanceof PrimitiveFloatList) {
      PrimitiveFloatList primitiveFloatList = (PrimitiveFloatList) list;
      int size = primitiveFloatList.size();
      FloatSupplierByIndex floatSupplierByIndex = primitiveFloatList::getPrimitive;
      return dotProduct(size, floatSupplierByIndex, floatSupplierByIndex);
    } else {
      int size = list.size();
      FloatSupplierByIndex floatSupplierByIndex = list::get;
      return dotProduct(size, floatSupplierByIndex, floatSupplierByIndex);
    }
  }

  /**
   *
   * @param record the record from which the value of the given field is extracted
   * @param field field which is used to extract the value from the given record
   * @param <T> Type of the list element to cast the extracted value to
   *
   * @return An unmodifiable empty list if the extracted value is null. Otherwise return a list that may or may not be
   *         modifiable depending on specified type of the list as a field in the record.
   */
  public static <T> List<T> getNullableFieldValueAsList(final GenericRecord record, Schema.Field field) {
    Object value = record.get(field.pos());
    if (value == null) {
      return Collections.emptyList();
    }
    if (!(value instanceof List)) {
      throw new IllegalArgumentException(
          String.format("Field %s in the record is not of the type list. Value: %s", field.name(), record));
    }
    return (List<T>) value;
  }

  /**
   * @return Error message if the nullable field validation failed or null otherwise.
   */
  public static String validateNullableFieldAndGetErrorMsg(
      ReadComputeOperator operator,
      GenericRecord valueRecord,
      Schema.Field operatorField,
      String operatorFieldName) {
    if (operatorField == null) {
      return "Failed to execute compute request as the field " + operatorFieldName
          + " does not exist in the value record. " + "Fields present in the value record are: "
          + getStringOfSchemaFieldNames(valueRecord);
    }
    if (valueRecord.get(operatorField.pos()) != null) {
      return null;
    }

    // Field exist and the value is null. That means the field is nullable.
    if (operator.allowFieldValueToBeNull()) {
      return null;
    }
    return "Failed to execute compute request as the field " + operatorFieldName + " is not allowed to be null for "
        + operator + " in value record.";
  }

  private static String getStringOfSchemaFieldNames(GenericRecord valueRecord) {
    List<String> fieldNames =
        valueRecord.getSchema().getFields().stream().map(Schema.Field::name).collect(Collectors.toList());
    return String.join(", ", fieldNames);
  }

  public static GenericRecord computeResult(
      List<ComputeOperation> operations,
      List<Schema.Field> operationResultFields,
      Map<String, Object> sharedContext,
      GenericRecord inputRecord,
      Schema outputSchema) {
    return computeResult(
        operations,
        operationResultFields,
        sharedContext,
        inputRecord,
        new GenericData.Record(outputSchema));
  }

  public static GenericRecord computeResult(
      List<ComputeOperation> operations,
      List<Schema.Field> operationResultFields,
      Map<String, Object> sharedContext,
      GenericRecord inputRecord,
      GenericRecord outputRecord) {
    if (inputRecord == null) {
      return null;
    }

    Map<String, String> errorMap = new HashMap<>();
    ComputeOperation computeOperation;
    ReadComputeOperator operator;
    String operatorFieldName, errorMessage;
    Schema.Field operatorField, resultField;
    for (int i = 0; i < operations.size(); i++) {
      computeOperation = operations.get(i);
      operator = ComputeOperationType.valueOf(computeOperation).getOperator();
      operatorFieldName = operator.getOperatorFieldName(computeOperation);
      operatorField = inputRecord.getSchema().getField(operatorFieldName);
      resultField = operationResultFields.get(i);
      errorMessage = validateNullableFieldAndGetErrorMsg(operator, inputRecord, operatorField, operatorFieldName);
      if (errorMessage != null) {
        operator.putDefaultResult(outputRecord, resultField);
        errorMap.put(operator.getResultFieldName(computeOperation), errorMessage);
        continue;
      }

      operator
          .compute(computeOperation, operatorField, resultField, inputRecord, outputRecord, errorMap, sharedContext);
    }

    Schema outputSchema = outputRecord.getSchema();
    Schema.Field errorMapField = outputSchema.getField(VeniceConstants.VENICE_COMPUTATION_ERROR_MAP_FIELD_NAME);
    if (errorMapField != null && outputRecord.get(errorMapField.pos()) == null) {
      outputRecord.put(errorMapField.pos(), errorMap);
    }

    Schema.Field inputRecordField;
    for (Schema.Field field: outputSchema.getFields()) {
      if (outputRecord.get(field.pos()) == null) {
        /**
         * N.B. Up until Avro 1.9, we could directly do {@code inputRecord.get(field.name())}, and it would work
         * even if that field name didn't exist in the {@link inputRecord} (merely returning null in that case),
         * but later versions of Avro throw instead, so we need to get the field and manually check whether it's
         * null (which is the same work that {@link GenericData.Record#get(String)} would do anyway).
         */
        inputRecordField = inputRecord.getSchema().getField(field.name());
        if (inputRecordField != null) {
          outputRecord.put(field.pos(), inputRecord.get(inputRecordField.pos()));
        }
      }
    }
    return outputRecord;
  }
}
