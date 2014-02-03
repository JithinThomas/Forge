import optiml.compiler._
import optiml.library._
import optiml.shared._

import scala.virtualization.lms.common.Record

object QPSCDCompiler extends OptiMLApplicationCompiler with QPSCD {
  val mBQP: Manifest[BQP] = manifest[BQP]
}
object QPSCDInterpreter extends OptiMLApplicationInterpreter with QPSCD {
  val mBQP: Manifest[BQP] = manifest[BQP]
}

trait QPSCD extends OptiMLApplication {
  val NUM_EPOCHS = 100
  val SYNC = 10

  type BQP = Record {
    val Q: DenseMatrix[Double]
    val p: DenseVector[Double]
    val lbound: DenseVector[Double]
    val ubound: DenseVector[Double]
    val diag: DenseVector[Double]
  }

  // workaround for erroneous or inaccessible type error with abstract typed record
  implicit val mBQP: Manifest[BQP]

  def newBQP(_Q: Rep[DenseMatrix[Double]], _p: Rep[DenseVector[Double]], _lbound: Rep[DenseVector[Double]], _ubound: Rep[DenseVector[Double]], _diag: Rep[DenseVector[Double]]): Rep[BQP] = new Record {
    val Q = _Q
    val p = _p
    val lbound = _lbound
    val ubound = _ubound
    val diag = _diag
  }


  /**
   * Single step of the SCD algorithm
   *
   *  @param q_i      The i-th row of Q
   *  @param i        Index of the co-ordinate
   *  @param p_i      The ith element of p vector
   *  @param q_ii     The ith diagonal entry of Q i.e Q[i,i]
   *  @param lb_i     The lower bound on the ith variable lb_i
   *  @param ub_i     The upper bound on the ith variable lb_i
   *  @param x        x-vector (Modified by reference)
   */
  def boundQPSCD(q_i: Rep[DenseVectorView[Double]], i: Rep[Int], p_i: Rep[Double], lb_i: Rep[Double], ub_i: Rep[Double], q_ii: Rep[Double], x: Rep[ForgeArray[Double]]) = {
    // memoize xk_i since we will write over it with x{k+1}_i
    val xk_i = x(i)
    // compute the ith component of the gradient
    // val d_i = DenseVector(x) *:* q_i // this creates an allocation in C-land, which can crash because we don't have mem management. why doesn't it fuse away? try:
    // val d_i = DenseVector(x.unsafeImmutable) *:* q_i // unfortunately unsafeImmutable loses the DeliteArrayNuma type. why?
    var d_i = 0.0
    var j = 0
    while (j < q_i.length) {
      d_i += x(j)*q_i(j)
      j += 1
    }
    val gradf_i = d_i + p_i
    // compute new value for the coordinate, with projection x^(k+1)_i
    val step = max(q_ii, 1e-6)
    val xkp1_i_cand = max(xk_i - gradf_i / step, lb_i)
    val xkp1_i = min(xkp1_i_cand, ub_i)

    x(i) = xkp1_i
  }

  /**
   * Performs 1 SCD Epoch on the given bqp
   * Assumes the bqp is normalized so diagonal entries are 1.
   *
   * @param x         the variable of optimization
   * @param bqp       the boxed QP to operate on
   * @param perm      the permutation to use
   */
  def boundQPSCDEpoch(x: Rep[ForgeArray[Double]], bqp: Rep[BQP], perm: Rep[IndexVector]) = {
    // HogWild! parallel with mutable, racy updates
    for (i <- 0::perm.length) {
      val idx = perm(i)
      boundQPSCD(bqp.Q(idx), idx, bqp.p(idx), bqp.lbound(idx), bqp.ubound(idx), bqp.diag(idx), x)
      ()
    }
  }

  def printUsage() {
    println("Usage: QPSCD <input data folder> <output file>")
    println("<input data folder> must contain files named Q.csv, p.csv, lb.csv, ub.csv")
    exit(-1)
  }

  def main() = {
    if (args.length < 2) printUsage()

    val in = args(0)
    val out = args(1)
    val Q = readMatrix(in + "/Q.csv", ",")
    val p = readVector(in + "/p.csv")
    val lb = readVector(in + "/lb.csv")
    val ub = readVector(in + "/ub.csv")
    val init_x = readVector(in + "/x.csv")

    val x = array_numa_empty[Double](p.length, ghost = ghostAll)
    for (i <- 0::init_x.length) {
      x(i) = init_x(i)
    }

    val perm = shuffle(0::p.length)
    val bqp = newBQP(Q, p, lb, ub, Q.diag)

    println("finished loading input. Q: " + Q.numRows + " x " + Q.numCols + ", p: " + p.length + ", lb: " + lb.length + ", ub: " + ub.length)
    tic(bqp)

    // toy inputs for testing
    // val x = DenseVector(1.,1.)
    // val perm = (0::2)
    // val bqp = newBQP(DenseMatrix((1.,2.),(3.,4.)), DenseVector(1.,1.), DenseVector(0.,0.), DenseVector(1.,1.), DenseVector(1.,4.))

    val x_star = {
      var i = 0
      while (i < NUM_EPOCHS) {
        if (i % SYNC == 0) {
          array_numa_combine_average(x)
        }
        boundQPSCDEpoch(x, bqp, perm)
        i += 1
      }
      x
    }


    val x_star_v = DenseVector(x_star)
    toc(x_star_v)
    writeVector(x_star_v, out)
    // println("found x_star: ")
    // x_star.pprint

    val err = sqrt(sum(square(Q*x_star_v.t + p)))
    println("error: " + err)
  }
}
