import javafx.application.Application
import javafx.beans.property.DoubleProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import tornadofx.*

class AppStarter : App(MainView::class)

class MainView : View("Jacobi Splines") {
	val defaultSize = 5
	val canvasWidth = 900.0
	val canvasHeight = 500.0

	val knotList = mutableListOf<DoubleProperty>().observable()

	val numSliders by lazy {
		val prop = SimpleIntegerProperty()
		prop.addListener { _, oldValue, newValue ->
			if (newValue.toInt() > oldValue.toInt()) {
				val count = newValue.toInt() - oldValue.toInt()
				for (i in 0 until count) {
					val point = SimpleDoubleProperty(0.0)
					point.onChange { draw() }
					knotList.add(point)
				}
			} else {
				val count = oldValue.toInt() - newValue.toInt()
				for (i in 0 until count) {
					knotList.removeAt(knotList.size - 1)
				}
			}

			draw()
		}
		prop
	}

	val knotDistance = canvasWidth.toProperty() / numSliders
	val knotDistanceHalf = knotDistance / 2

	lateinit var gc: GraphicsContext

	override val root = borderpane {
		paddingAll = 10
		setPrefSize(1024.0, 768.0)

		top = hbox(20) {
			alignment = Pos.CENTER_LEFT

			vbox {
				alignment = Pos.CENTER

				label("Num Points")
				spinner(2, 32, defaultSize, 1, true, numSliders, true) {
					prefWidth = 60.0
				}
			}

			separator(Orientation.VERTICAL)

			hbox(10) {
				bindChildren(knotList) {
					slider(-canvasHeight / 2, canvasHeight / 2, null, Orientation.VERTICAL) { bind(it) }
				}
			}
		}

		center = canvas(canvasWidth, canvasHeight) {
			gc = graphicsContext2D
			numSliders.value = defaultSize
		}
	}

	private fun draw() {
		gc.clearRect(0.0, 0.0, canvasWidth, canvasHeight)
		drawLine()
		drawKnots()
	}

	private fun drawLine() {
		val interpolator = JacobiKernel.jacobiSpline(knotList.map { it.value }.toDoubleArray(), knotDistance.value)
		var lastY = -interpolator(0.0) + canvasHeight / 2

		for (x in 1 until (canvasWidth - knotDistance.value).toInt()) {
			val currY = -interpolator(x.toDouble()) + canvasHeight / 2

			gc.strokeLine(
					x - 1.0 + knotDistanceHalf.value, lastY,
					x.toDouble() + knotDistanceHalf.value, currY
			)

			lastY = currY
		}
	}

	private fun drawKnots() {
		knotList.forEachIndexed { i, prop ->
			val pointThickness = 6.0
			val pointOffset = pointThickness / 2

			gc.fill = Color.RED

			gc.fillOval(
					knotDistance.value * i + knotDistanceHalf.value - pointOffset,
					-prop.value + canvasHeight / 2 - pointOffset,
					pointThickness,
					pointThickness
			)

			gc.fill = Color.BLACK
		}
	}
}

fun main(args: Array<String>) = Application.launch(AppStarter::class.java)