import Foundation
import CocoaMQTT

enum MQTTConfig {
    static let host = "homeassistant.local"
    static let port: UInt16 = 1883
    static let clientID = "ios-client-\(UUID().uuidString.prefix(6))"
    static let username = "aluno"
    static let password = "4luno#imd"
    static let topicDisponibilidade = "aha/ESP32_Wokwi_01/avty_t"
    static let maxTentativas = 3
}

final class MQTTService: NSObject {
    private static var client: CocoaMQTT?
    private static var conectado = false
    private static var tentativas = 0
    private static var ultimoValoresEnviados: [String: String] = [:]
    private static var configuracoesPublicadas: Set<String> = []

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

    static func publicarValores(labels: [String: Int], uniqueId: String) {
        guard conectado else {
            print("⚠️ MQTT não conectado. Mensagens não enviadas.")
            return
        }

        for (label, quantidade) in labels {
            let topico = "aha/object_detector/\(uniqueId)/\(label)/stat_t"
            let mensagem = "\(quantidade)"

            if ultimoValoresEnviados[label] == mensagem {
                print("🔁 \(label): valor repetido (\(mensagem)). Ignorando.")
                continue
            }

            if !configuracoesPublicadas.contains(label) {
                publicarConfiguracaoSensor(label: label, uniqueId: uniqueId)
            }

            client?.publish(topico, withString: mensagem, qos: .qos1)
            ultimoValoresEnviados[label] = mensagem
            print("📤 \(label): \(mensagem) → \(topico)")
        }
    }

    private static func publicarConfiguracaoSensor(label: String, uniqueId: String) {
        let component = ComponentEntry(
            platform: "sensor",
            name: label,
            stat_t: "aha/object_detector/\(uniqueId)/\(label)/stat_t",
            val_tpl: "{{ value }}",
            unit_of_meas: label,
            uniq_id: "\(uniqueId)_\(label)"
        )

        let device = DeviceEntry(
            ids: [uniqueId],
            name: "Object Detector \(uniqueId)",
            mf: "IoT PPGTI",
            mdl: "EdgeCamera v1",
            sw: "1.0.0"
        )

        let origin = OriginEntry(
            name: "Object Detector",
            sw: "1.0.0"
        )

        let config = ConfigEntry(
            cmps: [label: component],
            o: origin,
            dev: device
        )

        guard let jsonData = try? JSONEncoder().encode(config),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            print("❌ Erro ao codificar configuração de \(label).")
            return
        }

        let configTopic = "homeassistant/device/object_detector/\(uniqueId)/config"
        client?.publish(configTopic, withString: jsonString, qos: .qos1, retained: true)
        configuracoesPublicadas.insert(label)
        print("📤 Configuração publicada para label '\(label)' em \(configTopic)")
    }

    static func publicar(topico: String, mensagem: String, qos: CocoaMQTTQoS = .qos1, retained: Bool = false) {
        guard conectado else {
            print("⚠️ MQTT não conectado. Mensagem não enviada para \(topico)")
            return
        }
        client?.publish(topico, withString: mensagem, qos: qos, retained: retained)
        print("📤 Mensagem publicada no tópico \(topico): \(mensagem)")
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
        configuracoesPublicadas.removeAll()
        ultimoValoresEnviados.removeAll()
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
            MQTTService.configuracoesPublicadas.removeAll()
            MQTTService.ultimoValoresEnviados.removeAll()
            print("🔌 Desconectado: \(err?.localizedDescription ?? "sem erro")")
        }

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

// MARK: - Estruturas JSON compatíveis com o modelo Android

struct ComponentEntry: Codable {
    let platform: String
    let name: String
    let stat_t: String
    let val_tpl: String
    let unit_of_meas: String
    let uniq_id: String
}

struct DeviceEntry: Codable {
    let ids: [String]
    let name: String
    let mf: String
    let mdl: String
    let sw: String
}

struct OriginEntry: Codable {
    let name: String
    let sw: String
}

struct ConfigEntry: Codable {
    let cmps: [String: ComponentEntry]
    let o: OriginEntry
    let dev: DeviceEntry
}
