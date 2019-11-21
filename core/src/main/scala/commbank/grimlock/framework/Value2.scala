// Copyright 2014,2015,2016,2017,2018,2019 Commonwealth Bank of Australia
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package commbank.grimlock.framework.encoding2

/** Trait for variable values. */
trait Value[T] {
  /** The schema used to validate and encode/decode this value. */
  val schema: Schema[T]

  /** The encapsulated value. */
  val value: T

  if (!schema.validate(value))
    throw new IllegalArgumentException(s"${value} does not conform to schema ${schema.toShortString}")
}

case class DecimalValue(value: BigDecimal, schema: Schema[BigDecimal]) extends Value[BigDecimal] {
  // TODO add comparison methods
}

case class DoubleValue(value: Double, schema: Schema[Double]) extends Value[Double] {
  // TODO add comparison methods
}

