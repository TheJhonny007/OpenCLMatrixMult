import com.jogamp.opencl.*
import org.jocl.Sizeof
import java.nio.FloatBuffer
import java.util.*
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis


object JacobiKernel {
	private val context: CLContext
	private lateinit var queue: CLCommandQueue
	private lateinit var program: CLProgram
	private lateinit var initKernel: CLKernel
	private lateinit var jacobiStepKernelA: CLKernel
	private lateinit var jacobiStepKernelB: CLKernel

	private lateinit var differenceKernel: CLKernel

	private lateinit var initRHSKernel: CLKernel
	private lateinit var jacobiSplineStepKernelA: CLKernel
	private lateinit var jacobiSplineStepKernelB: CLKernel
	private lateinit var computeABKernel: CLKernel

	private var maxLocalWorkSize1D = 256
	private var maxLocalWorkSize2D = 8

	private const val randomRange = 100.0

	init {
		context = clContext {
			println(platform)
			println(maxFlopsDevice)
			println()
			queue = maxFlopsDevice.createCommandQueue()
			program = createProgram("kernel.cl".asFileStream()).build()

			maxLocalWorkSize1D = maxFlopsDevice.maxWorkGroupSize
			maxLocalWorkSize2D = sqrt(maxFlopsDevice.maxWorkGroupSize.toDouble()).toInt()

			initKernel = program.createCLKernel("init")
			jacobiStepKernelA = program.createCLKernel("jacobiStep")
			jacobiStepKernelB = program.createCLKernel("jacobiStep")

			differenceKernel = program.createCLKernel("difference")

			initRHSKernel = program.createCLKernel("initRHS")
			jacobiSplineStepKernelA = program.createCLKernel("jacobiSplineStep")
			jacobiSplineStepKernelB = program.createCLKernel("jacobiSplineStep")
			computeABKernel = program.createCLKernel("computeAB")
		}
	}

	fun jacobi(dimension: Int, matA: FloatArray? = null, vecB: FloatArray? = null) {
		context {
			val localWorkSizeD = min(dimension, maxLocalWorkSize1D)
			val globalWorkSizeD = roundUp(localWorkSizeD, dimension)
			val globalWorkSizeDxD = globalWorkSizeD * globalWorkSizeD

			val xOld = createFloatBuffer(globalWorkSizeD, CLMemory.Mem.READ_WRITE)
			val xNew = createFloatBuffer(globalWorkSizeD, CLMemory.Mem.READ_WRITE)

			val A = createFloatBuffer(globalWorkSizeDxD, CLMemory.Mem.READ_ONLY)
			val b = createFloatBuffer(globalWorkSizeD, CLMemory.Mem.READ_ONLY)
			val diff = createFloatBuffer(globalWorkSizeD / localWorkSizeD, CLMemory.Mem.READ_WRITE)

			if(matA != null) {
				for (value in matA)
					A.buffer.put(value)
				A.buffer.rewind()
			} else {
				fillA(dimension, A.buffer)
			}

			if(vecB != null) {
				for (value in vecB)
					b.buffer.put(value)
				b.buffer.rewind()
			} else {
				fillBuffer(b.buffer)
			}

			if (dimension < 64)
				println(getEquationAsString(A.buffer, b.buffer, dimension))

			initKernel.args {
				arg(xOld)
				rewind()
			}

			jacobiStepKernelA.args {
				arg(A)
				arg(b)
				arg(xNew)
				arg(xOld)
				arg(dimension)
				rewind()
			}

			jacobiStepKernelB.args {
				arg(A)
				arg(b)
				arg(xOld)
				arg(xNew)
				arg(dimension)
				rewind()
			}

			differenceKernel.args {
				arg(xOld)
				arg(xNew)
				arg(diff)
				local(localWorkSizeD * Sizeof.cl_float)
				rewind()
			}

			queue.enqueue {
				writeBuffer(xOld)
				kernel1DRange(initKernel, 0, dimension.toLong(), dimension.toLong())

				writeBuffer(A)
				writeBuffer(b)
				writeBuffer(xNew)
				writeBuffer(diff)

				flush()
				finish()
			}

			var currIt = 0
			val maxIt = 100
			val eps = 1e-20
			do {
				queue.enqueue {
					kernel1DRange(jacobiStepKernelA, 0, globalWorkSizeDxD.toLong(), localWorkSizeD.toLong())
					flush()
					finish()

					kernel1DRange(jacobiStepKernelB, 0, globalWorkSizeDxD.toLong(), localWorkSizeD.toLong())
					flush()
					finish()

					kernel1DRange(differenceKernel, 0, globalWorkSizeD.toLong(), localWorkSizeD.toLong())
					readBuffer(diff)
					flush()
					finish()
				}
			} while (reduceDiffBuffer(diff.buffer) > eps && currIt++ < maxIt)

			queue.enqueue {
				readBuffer(xOld)
				flush()
				finish()
			}

			println("Result after $currIt steps:")
			for (i in 0 until dimension) {
				val extension = if (dimension < 27) "${'a' + i}" else "x${i.toSubscriptString()}"
				print("$extension = ${String.format("%f", xOld.buffer[i])}${if (i < dimension - 1) ", " else ""}")
			}
			println()
		}
	}

	fun jacobiSpline(yArray: DoubleArray, h: Double) = jacobiSpline(yArray.size, h.toFloat(), yArray)

	fun jacobiSpline(dimension: Int, h: Float, yArray: DoubleArray? = null) : JacobiInterpolator {
		var result: JacobiInterpolator? = null

		context {
			val localWorkSizeD = min(dimension, maxLocalWorkSize1D)
			val globalWorkSizeD = roundUp(localWorkSizeD, dimension)

			val a = createFloatBuffer(globalWorkSizeD, CLMemory.Mem.READ_WRITE)
			val b = createFloatBuffer(globalWorkSizeD, CLMemory.Mem.READ_WRITE)
			val c1 = createFloatBuffer(globalWorkSizeD, CLMemory.Mem.READ_WRITE)
			val c2 = createFloatBuffer(globalWorkSizeD, CLMemory.Mem.READ_WRITE)
			val rhs = createFloatBuffer(globalWorkSizeD, CLMemory.Mem.READ_WRITE)

			val y = createFloatBuffer(globalWorkSizeD, CLMemory.Mem.READ_ONLY)

			val diff = createFloatBuffer(globalWorkSizeD / localWorkSizeD, CLMemory.Mem.READ_WRITE)

			if(yArray == null) {
				fillBuffer(y.buffer)
			} else {
				yArray.forEach {
					y.buffer.put(it.toFloat())
				}

				y.buffer.rewind()
			}

			initKernel.args {
				arg(c1)
				rewind()
			}
			queue.enqueue {
				writeBuffer(c1)
				kernel1DRange(initKernel, 0, globalWorkSizeD.toLong(), localWorkSizeD.toLong())
				flush()
				finish()
			}

			c2.buffer.put(0, 0.0f)

			initRHSKernel.args {
				arg(y)
				arg(rhs)
				arg(h)
				rewind()
			}

			jacobiSplineStepKernelA.args {
				arg(rhs)
				arg(c1)
				arg(c2)
				rewind()
			}

			jacobiSplineStepKernelB.args {
				arg(rhs)
				arg(c2)
				arg(c1)
				rewind()
			}

			computeABKernel.args {
				arg(y)
				arg(c1)
				arg(a)
				arg(b)
				arg(h)
				rewind()
			}

			differenceKernel.args {
				arg(c1)
				arg(c2)
				arg(diff)
				local(localWorkSizeD * Sizeof.cl_float)
				rewind()
			}

			queue.enqueue {
				writeBuffer(y)
				writeBuffer(rhs)
				writeBuffer(a)
				writeBuffer(b)
				writeBuffer(diff)

				kernel1DRange(initRHSKernel, 0, globalWorkSizeD.toLong(), localWorkSizeD.toLong())

				flush()
				finish()
			}

			var currIt = 0
			val maxIt = 100
			val eps = 1e-10
			do {
				queue.enqueue {
					kernel1DRange(jacobiSplineStepKernelA, 0, globalWorkSizeD.toLong(), localWorkSizeD.toLong())
					flush()
					finish()

					kernel1DRange(jacobiSplineStepKernelB, 0, globalWorkSizeD.toLong(), localWorkSizeD.toLong())
					flush()
					finish()

					kernel1DRange(differenceKernel, 0, globalWorkSizeD.toLong(), localWorkSizeD.toLong())
					readBuffer(diff)
					flush()
					finish()
				}
			} while (reduceDiffBuffer(diff.buffer) > eps && currIt++ < maxIt)

			println(currIt)

			queue.enqueue {
				kernel1DRange(computeABKernel, 0, globalWorkSizeD.toLong(), localWorkSizeD.toLong())

				readBuffer(a)
				readBuffer(b)
				readBuffer(c1)

				flush()
				finish()
			}

			result = JacobiInterpolator(
					a.buffer.toDoubleArray(),
					b.buffer.toDoubleArray(),
					c1.buffer.toDoubleArray(),
					h.toDouble())

			a.release()
			b.release()
			c1.release()
			c2.release()
			rhs.release()
			y.release()
			diff.release()
		}

		return result ?: throw IllegalStateException("Calculation failed")
	}

	private fun reduceDiffBuffer(floatBuffer: FloatBuffer): Double {
		var sum = 0.0
		while (floatBuffer.hasRemaining()) {
			sum += floatBuffer.get()
		}

		floatBuffer.rewind()
		return sum
	}

	private fun fillA(dimension: Int, buffer: FloatBuffer) {
		val random = Random()
		while (buffer.remaining() != 0)
			buffer.put(random.nextFloat() * randomRange.toFloat())
		buffer.rewind()

		for (i in 0 until dimension)
			buffer.put(i * dimension + i, ((1 + random.nextFloat() + dimension * dimension) * randomRange).toFloat())
		buffer.rewind()
	}

	private fun getEquationAsString(a: FloatBuffer, b: FloatBuffer, dimension: Int): String = buildString {
		for (row in 0 until dimension) {
			for (col in 0 until dimension) {
				val extension = if (dimension < 27) "${'a' + col}" else "x${col.toSubscriptString()}"
				if (col != 0) append(" ")
				append("${a[row * dimension + col].format(ceil(log10((dimension * dimension + 2) * randomRange)).toInt() + 5, 4)} $extension ")
				if (col < dimension - 1) append('+')
			}
			append("= ${b[row].format(7, 4)}\n")
		}
	}

	private fun roundUp(localWorkSize: Int, elements: Int): Int {
		val r = elements % localWorkSize
		return if (r == 0)
			elements
		else
			elements + localWorkSize - r
	}

	private fun fillBuffer(buffer: FloatBuffer) {
		val random = Random()
		while (buffer.remaining() != 0)
			buffer.put(random.nextFloat() * randomRange.toFloat())
		buffer.rewind()
	}
}

class JacobiInterpolator(val a: DoubleArray, val b: DoubleArray, val c: DoubleArray, val h: Double) {
	operator fun invoke(x: Double): Double {
		val i = (x / h).toInt() + 1
		val lowerBound = (i - 1) * h
		val upperBound = lowerBound + h

		return (1.0 / (6.0 * h)) * c[i] * ((x - lowerBound) * (x - lowerBound) * (x - lowerBound)) +
			   (1.0 / (6.0 * h)) * c[i - 1] * ((upperBound - x) * (upperBound - x) * (upperBound - x)) +
				b[i] * (x - 0.5 * (lowerBound + upperBound)) + a[i]
	}
}

fun main(args: Array<String>) {
	jacobiExamples()
}

fun jacobiExamples() {
	run {
		val A = arrayOf(
				2.0f, 3.0f,
				4.0f, 9.0f
		).toFloatArray()

		val b = arrayOf(
				6.0f,
				15.0f
		).toFloatArray()
		JacobiKernel.jacobi(b.size, A, b)
	}

	println("\n\n############\n")

	run {
		val A = arrayOf(
				37f,   2f,  -1f,  1f,
				2f, -32f,   4f,  2f,
				-1f,  .5f, -36f,  3f,
				1f,   3f,   1f, 37f
		).toFloatArray()
		val b = arrayOf(
				1f,
				-2f,
				0f,
				1f
		).toFloatArray()
		JacobiKernel.jacobi(b.size, A, b)
	}

	println("\n\n############\n")
	JacobiKernel.jacobi(32)

	println("\n\n############\n")
	println("\nEquation system of size 1024 took ${measureTimeMillis { JacobiKernel.jacobi(1024) }}ms to solve")
}