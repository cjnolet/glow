package com.databricks.vcf

import java.util.{HashMap => JHashMap, List => JList, Map => JMap}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import htsjdk.samtools.ValidationStringency
import htsjdk.variant.variantcontext.{Allele, VariantContext, Genotype => HTSJDKGenotype}
import htsjdk.variant.vcf._
import org.apache.spark.sql.catalyst.expressions.{
  GenericInternalRow,
  SpecificInternalRow,
  UnsafeProjection,
  UnsafeRow
}
import org.apache.spark.sql.catalyst.util.{ArrayBasedMapData, ArrayData, GenericArrayData}
import org.apache.spark.sql.catalyst.{InternalRow, ScalaReflection}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

import com.databricks.hls.common.HLSLogging

/**
 * Infers the schema of a VCF file from its headers.
 */
object VCFSchemaInferer extends HLSLogging {

  /**
   * @param includeSampleIds If true, a sampleId column will be added to the genotype fields
   * @param flattenInfoFields If true, each INFO field will be promoted to a column. If false,
   *                          they will instead be stored in a string -> string map
   * @return A StructType describing the schema
   */
  def inferSchema(
      includeSampleIds: Boolean,
      flattenInfoFields: Boolean,
      infoHeaders: Seq[VCFInfoHeaderLine],
      formatHeaders: Seq[VCFFormatHeaderLine]): StructType = {
    val validatedInfoHeaders = validateHeaders(infoHeaders)
    val validatedFormatHeaders = validateHeaders(formatHeaders)
    val withInfoFields = if (flattenInfoFields) {
      validatedInfoHeaders.foldLeft(VariantSchemas.vcfBaseSchema) {
        case (schema, line) =>
          val field = StructField("INFO_" + line.getID, typeForHeader(line))
          schema.add(field)
      }
    } else {
      VariantSchemas.vcfBaseSchema.add(StructField("attributes", MapType(StringType, StringType)))
    }

    var genotypeStruct = StructType(Seq.empty)
    if (includeSampleIds) {
      genotypeStruct = genotypeStruct.add(VariantSchemas.sampleIdField)
    }
    validatedFormatHeaders.foreach { line =>
      val field = StructField(line.getID, typeForHeader(line))
      val name = GenotypeFields.aliases.getOrElse(field.name, field.name)
      genotypeStruct = genotypeStruct.add(field.copy(name = name))
    }

    withInfoFields.add(StructField("genotypes", ArrayType(genotypeStruct)))
  }

  def typeForHeader(line: VCFCompoundHeaderLine): DataType = {
    if (particularSchemas.contains(line.getID)) {
      return particularSchemas(line.getID)
    }

    val primitiveType = line.getType match {
      case VCFHeaderLineType.Character => StringType
      case VCFHeaderLineType.String => StringType
      case VCFHeaderLineType.Float => DoubleType
      case VCFHeaderLineType.Integer => IntegerType
      case VCFHeaderLineType.Flag => BooleanType
    }

    if (line.isFixedCount && line.getCount <= 1) {
      primitiveType
    } else {
      ArrayType(primitiveType)
    }
  }

  /**
   * Given a group of headers, ensures that there are no incompatible duplicates (e.g., same name
   * but different type or count).
   * @return A seq of unique headers
   */
  private def validateHeaders(headers: Seq[VCFCompoundHeaderLine]): Seq[VCFCompoundHeaderLine] = {
    headers
      .groupBy(line => line.getID)
      .map {
        case (id, lines) =>
          if (!lines.tail.forall(_.equalsExcludingDescription(lines.head))) {
            // Some headers with same key but different types
            throw new IllegalArgumentException(s"VCF headers with id $id have incompatible schemas")
          }
          lines.head
      }
      .toSeq
  }

  // Fields for which the schema cannot be inferred from the VCF header
  private val particularSchemas: Map[String, StructType] = Map(
    "GT" -> Genotype.schema
  )
}