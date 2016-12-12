package io.citrine.lolo

import io.citrine.lolo.results.PredictionResult

/**
  * Created by maxhutch on 11/14/16.
  */
abstract class Model extends Serializable {

  /**
    * Apply the model to a seq of inputs
    *
    * @param inputs to apply the model to
    * @return a predictionresult which includes, at least, the expected outputs
    */
  def transform(inputs: Seq[Vector[Any]]): PredictionResult[Any]
}
