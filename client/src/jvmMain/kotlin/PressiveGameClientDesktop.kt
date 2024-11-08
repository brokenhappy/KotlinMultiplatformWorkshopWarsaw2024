import kmpworkshop.client.AdaptingBackground
import kmpworkshop.client.PressiveGame


fun main() {
    WorkshopApp("Pressive Game Client") { AdaptingBackground { PressiveGame() } }
}