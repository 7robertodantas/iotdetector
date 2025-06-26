import Foundation
import CocoaMQTT

enum MQTTConfig {
    static let host = "homeassistant.local"
    static let port: UInt16 = 1883
    static let clientID = "ios-client-\(UUID().uuidString.prefix(6))"
    static let username = "aluno"
    static let password = "4luno#imd"
    static let topicEstado = "appmobile/pessoas"
    static let topicDisponibilidade = "aha/ESP32_Wokwi_01/avty_t"
    static let maxTentativas = 3
}

final class MQTTService: NSObject {
    private static var client: CocoaMQTT?
    private static var conectado = false
    private static var tentativas = 0
    private static var ultimoValorEnviado: String?

    static func conectar() {
        let mqtt = CocoaMQTT(clientID: MQTTConfig.clientID, host: MQTTConfig.host, port: MQTTConfig.port)
        mqtt.username = MQTTConfig.username
        mqtt.password = MQTTConfig.password
        mqtt.keepAlive = 60
        mqtt.cleanSession = true
        mqtt.autoReconnect = false
        mqtt.delegate = MQTTDelegateHandler.shared

        client = mqtt
        tentarConectar()
    }

    private static func tentarConectar() {
        guard tentativas < MQTTConfig.maxTentativas else {
            print("❌ Não foi possível conectar após \(MQTTConfig.maxTentativas) tentativas.")
            return
        }

        tentativas += 1
        print("🔁 Tentando conectar... tentativa \(tentativas)")
        client?.connect()
    }

    static func publicarQuantidadePessoas(_ quantidade: Int) {
        guard conectado else {
            print("⚠️ MQTT não conectado. Mensagem não enviada.")
            return
        }

        let mensagem = String(quantidade)
        guard mensagem != ultimoValorEnviado else {
            print("🔁 Valor repetido. Ignorando envio.")
            return
        }

        client?.publish(MQTTConfig.topicEstado, withString: mensagem, qos: .qos1)
        ultimoValorEnviado = mensagem
        print("📤 Pessoas detectadas: \(mensagem)")
    }

    private static func publicarDisponibilidade(_ status: String) {
        client?.publish(MQTTConfig.topicDisponibilidade, withString: status, qos: .qos1, retained: true)
        print("📶 Disponibilidade publicada: \(status)")
    }

    static func setDisponibilidadeOnline() { publicarDisponibilidade("online") }
    static func setDisponibilidadeOffline() { publicarDisponibilidade("offline") }

    static func desconectar() {
        publicarDisponibilidade("offline")
        client?.disconnect()
        conectado = false
        print("🔌 Cliente MQTT desconectado manualmente.")
    }

    @objcMembers
    fileprivate class MQTTDelegateHandler: NSObject, CocoaMQTTDelegate {
        static let shared = MQTTDelegateHandler()

        func mqtt(_ mqtt: CocoaMQTT, didConnectAck ack: CocoaMQTTConnAck) {
            if ack == .accept {
                MQTTService.conectado = true
                MQTTService.tentativas = 0
                print("✅ Conectado ao MQTT com sucesso.")
                MQTTService.publicarDisponibilidade("online")
            } else {
                print("❌ Conexão recusada: \(ack)")
            }
        }

        func mqtt(_ mqtt: CocoaMQTT, didPublishMessage message: CocoaMQTTMessage, id: UInt16) {
            print("📤 Mensagem publicada: \(message.string ?? "") em \(message.topic)")
        }

        func mqtt(_ mqtt: CocoaMQTT, didPublishAck id: UInt16) {
            print("✅ ACK de publicação recebido (id \(id))")
        }

        func mqtt(_ mqtt: CocoaMQTT, didReceiveMessage message: CocoaMQTTMessage, id: UInt16) {
            print("📥 Mensagem recebida: \(message.string ?? "") de \(message.topic)")
        }

        func mqtt(_ mqtt: CocoaMQTT, didSubscribeTopics success: NSDictionary, failed: [String]) {
            print("✅ Subscrição bem-sucedida: \(success.allKeys)")
            if !failed.isEmpty {
                print("⚠️ Falha na subscrição: \(failed)")
            }
        }

        func mqtt(_ mqtt: CocoaMQTT, didUnsubscribeTopics topics: [String]) {
            print("🚫 Tópicos cancelados: \(topics)")
        }

        func mqttDidPing(_ mqtt: CocoaMQTT) {
            print("📶 Ping enviado para o broker.")
        }

        func mqttDidReceivePong(_ mqtt: CocoaMQTT) {
            print("🏓 Pong recebido do broker.")
        }

        func mqttDidDisconnect(_ mqtt: CocoaMQTT, withError err: Error?) {
            MQTTService.conectado = false
            print("🔌 Desconectado: \(err?.localizedDescription ?? "sem erro")")
        }

        // Métodos opcionais implementados por segurança
        func mqtt(_ mqtt: CocoaMQTT, didReceive trust: SecTrust, completionHandler: @escaping (Bool) -> Void) {
            completionHandler(true)
        }

        func mqtt(_ mqtt: CocoaMQTT, didPublishComplete id: UInt16) {
            print("✅ Publicação completa (id \(id))")
        }

        func mqtt(_ mqtt: CocoaMQTT, didStateChangeTo state: CocoaMQTTConnState) {
            print("📶 Estado da conexão: \(state)")
        }
    }


}

