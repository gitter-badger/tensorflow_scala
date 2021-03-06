/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.tensorflow.api

/** Groups together all the implicits of the API and takes care of their priorities.
  *
  * @author Emmanouil Antonios Platanios
  */
private[api] trait Implicits
    extends LowPriorityImplicits
        with core.Implicits

private[api] trait LowPriorityImplicits
    extends LowestPriorityImplicits
        with tensors.Implicits

private[api] trait LowestPriorityImplicits
    extends ops.Implicits
        with learn.Implicits

private[api] object Implicits extends Implicits
